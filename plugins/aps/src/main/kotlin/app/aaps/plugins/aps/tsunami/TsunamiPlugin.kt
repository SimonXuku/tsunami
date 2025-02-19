package app.aaps.plugins.aps.tsunami

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileTsunami
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.Preferences
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.objects.aps.DetermineBasalResult
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

@Singleton
open class TsunamiPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalTsunami: DetermineBasalTsunami,
    private val profiler: Profiler,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.tsunami)
        .shortName(R.string.tsunami_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList(showInList = {config.isEngineeringMode() && config.isDev()})
        //.showInList(config.APS)
        .description(R.string.description_tsunami)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {

override fun onStart() {
    super.onStart()
    var count = 0
    val apsResults = persistenceLayer.getApsResults(dateUtil.now() - T.days(1).msecs(), dateUtil.now())
    apsResults.forEach {
        val glucose = it.glucoseStatus?.glucose ?: return@forEach
        val variableSens = it.variableSens ?: return@forEach
        val timestamp = it.date
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        if (variableSens > 0) dynIsfCache.put(key, variableSens)
        count++
    }
    aapsLogger.debug(LTag.APS, "Loaded $count variable sensitivity values from database")
}

    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.TSUNAMI
    override var lastAPSResult: DetermineBasalResult? = null
    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)

    override fun getIsfMgdl(profile: Profile, caller: String): Double? {
        val start = dateUtil.now()
        val multiplier = (profile as ProfileSealed.EPS).value.originalPercentage / 100.0
        val sensitivity = calculateVariableIsf(start, multiplier)
        if (sensitivity.second == null)
            uiInteraction.addNotificationValidTo(
                Notification.DYN_ISF_FALLBACK, start,
                rh.gs(R.string.fallback_to_isf_no_tdd, sensitivity.first), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
            )
        else
            uiInteraction.dismissNotification(Notification.DYN_ISF_FALLBACK)
        profiler.log(LTag.APS, "getIsfMgdl() multiplier=${multiplier} reason=${sensitivity.first} sensitivity=${sensitivity.second} caller=$caller", start)
        return sensitivity.second
    }

    override fun getAverageIsfMgdl(timestamp: Long, caller: String): Double? {
        var count = 0
        var sum = 0.0
        val start = timestamp - T.hours(24).msecs()
        dynIsfCache.forEach { key, value ->
            if (key in start..timestamp) {
                count++
                sum += value
            }
        }
        val sensitivity = if (count == 0) null else sum / count
        aapsLogger.debug(LTag.APS, "getAverageIsfMgdl() $sensitivity from $count values ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $caller")
        return sensitivity
    }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        try {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()
        val autoSensOrDynIsfSensEnabled = if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)
        } else {
            preferences.get(BooleanKey.ApsUseAutosens)
        }

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }

    private val dynIsfCache = LongSparseArray<Double>()

    @Synchronized
    private fun calculateVariableIsf(timestamp: Long, multiplier: Double): Pair<String, Double?> {
        if (!preferences.get(BooleanKey.ApsUseDynamicSensitivity)) return Pair("OFF", null)

        val result = persistenceLayer.getApsResultCloseTo(timestamp)
        if (result?.variableSens != null && result.variableSens != 0.0) {
            //aapsLogger.debug("calculateVariableIsf $caller DB  ${dateUtil.dateAndTimeAndSecondsString(timestamp)} ${result.variableSens}")
            return Pair("DB", result.variableSens)
        }

        val glucose = glucoseStatusProvider.glucoseStatusData?.glucose ?: return Pair("GLUC", null)
        // Round down to 30 min and use it as a key for caching
        // Add BG to key as it affects calculation
        val key = timestamp - timestamp % T.mins(30).msecs() + glucose.toLong()
        val cached = dynIsfCache[key]
        if (cached != null && timestamp < dateUtil.now()) {
            //aapsLogger.debug("calculateVariableIsf $caller HIT ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $cached")
            return Pair("HIT", cached)
        }

        val dynIsfResult = calculateRawDynIsf(multiplier)
        if (!dynIsfResult.tddPartsCalculated()) return Pair("TDD miss", null)
        // no cached result found, let's calculate the value
        //aapsLogger.debug("calculateVariableIsf $caller CAL ${dateUtil.dateAndTimeAndSecondsString(timestamp)} $sensitivity")
        dynIsfCache.put(key, dynIsfResult.variableSensitivity)
        if (dynIsfCache.size() > 1000) dynIsfCache.clear()
        return Pair("CALC", dynIsfResult.variableSensitivity)
    }

    internal class DynIsfResult {

        var tdd1D: Double? = null
        var tdd7D: Double? = null
        var tddLast24H: Double? = null
        var tddLast4H: Double? = null
        var tddLast8to4H: Double? = null
        var tdd: Double? = null
        var variableSensitivity: Double? = null
        var insulinDivisor: Int = 0

        var tddLast24HCarbs = 0.0
        var tdd7DDataCarbs = 0.0
        var tdd7DAllDaysHaveCarbs = false

        fun tddPartsCalculated() = tdd1D != null && tdd7D != null && tddLast24H != null && tddLast4H != null && tddLast8to4H != null

        fun log() =
            "DynIsfResult: tdd1D=$tdd1D tdd7D=$tdd7D tddLast24H=$tddLast24H tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tdd=$tdd variableSensitivity=$variableSensitivity insulinDivisor=$insulinDivisor tdd7DDataCarbs=$tdd7DDataCarbs tdd7DAllDaysHaveCarbs=$tdd7DAllDaysHaveCarbs"
    }

    private fun calculateRawDynIsf(multiplier: Double): DynIsfResult {
        val dynIsfResult = DynIsfResult()
        // DynamicISF specific
        // without these values DynISF doesn't work properly
        // Current implementation is fallback to SMB if TDD history is not available. Thus calculated here
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        dynIsfResult.tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.data?.totalAmount
        tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.let {
            dynIsfResult.tdd7D = it.data.totalAmount
            dynIsfResult.tdd7DDataCarbs = it.data.carbs
            dynIsfResult.tdd7DAllDaysHaveCarbs = it.allDaysHaveCarbs
        }
        tddCalculator.calculateDaily(-24, 0)?.also {
            dynIsfResult.tddLast24H = it.totalAmount
            dynIsfResult.tddLast24HCarbs = it.carbs
        }
        dynIsfResult.tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        dynIsfResult.tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        val insulin = activePlugin.activeInsulin
        dynIsfResult.insulinDivisor = when {
            insulin.peak > 65 -> 55 // rapid peak: 75
            insulin.peak > 50 -> 65 // ultra rapid peak: 55
            else              -> 75 // lyumjev peak: 45
        }


        if (dynIsfResult.tddPartsCalculated() && glucoseStatus != null) {
            val tddStatus = TddStatus(dynIsfResult.tdd1D!!, dynIsfResult.tdd7D!!, dynIsfResult.tddLast24H!!, dynIsfResult.tddLast4H!!, dynIsfResult.tddLast8to4H!!)
            val tddWeightedFromLast8H = ((1.4 * tddStatus.tddLast4H) + (0.6 * tddStatus.tddLast8to4H)) * 3
            dynIsfResult.tdd = ((tddWeightedFromLast8H * 0.33) + (tddStatus.tdd7D * 0.34) + (tddStatus.tdd1D * 0.33)) * preferences.get(IntKey.ApsDynIsfAdjustmentFactor) / 100.0 * multiplier
            dynIsfResult.variableSensitivity = Round.roundTo(1800 / (dynIsfResult.tdd!! * (ln((glucoseStatus.glucose / dynIsfResult.insulinDivisor) + 1))), 0.1)
            aapsLogger.debug(LTag.APS, "multiplier=$multiplier dynIsfResult=${dynIsfResult.log()} glucoseStatus=${glucoseStatus.glucose} insulinDivisor=${dynIsfResult.insulinDivisor}")
        }
        return dynIsfResult
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("TsunamiPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data

        val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity) && hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(), R.string.dyn_isf_adjust_title, IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(), IntKey.ApsDynIsfAdjustmentFactor.max.toDouble())
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        var autosensResult = AutosensResult()
        var dynIsfResult: DynIsfResult? = null
        // var variableSensitivity = 0.0
        // var tdd = 0.0
        // var insulinDivisor = 0
        dynIsfResult = calculateRawDynIsf((profile as ProfileSealed.EPS).value.originalPercentage / 100.0)
        if (dynIsfMode && !dynIsfResult.tddPartsCalculated()) {
                uiInteraction.addNotificationValidTo(
                    Notification.SMB_FALLBACK, dateUtil.now(),
                    rh.gs(R.string.fallback_smb_no_tdd), Notification.INFO, dateUtil.now() + T.mins(1).msecs()
                )
                inputConstraints.copyReasons(
                    ConstraintObject(false, aapsLogger).also {
                        it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this)
                    }
                )
                inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply {
                    set(true, "tdd1D=${dynIsfResult.tdd1D} tdd7D=${dynIsfResult.tdd7D} tddLast4H=${dynIsfResult.tddLast4H} tddLast8to4H=${dynIsfResult.tddLast8to4H} tddLast24H=${dynIsfResult.tddLast24H}", this)
                }
                )
        }
        if (dynIsfMode && dynIsfResult.tddPartsCalculated()) {
                uiInteraction.dismissNotification(Notification.SMB_FALLBACK)
                // Compare insulin consumption of last 24h with last 7 days average
            val tddRatio = if (preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity)) dynIsfResult.tddLast24H!! / dynIsfResult.tdd7D!! else 1.0
                // Because consumed carbs affects total amount of insulin compensate final ratio by consumed carbs ratio
                // take only 60% (expecting 40% basal). We cannot use bolus/total because of SMBs
                val carbsRatio = if (
                    preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity) &&
                dynIsfResult.tddLast24HCarbs != 0.0 &&
                dynIsfResult.tdd7DDataCarbs != 0.0 &&
                dynIsfResult.tdd7DAllDaysHaveCarbs
            ) ((dynIsfResult.tddLast24HCarbs / dynIsfResult.tdd7DDataCarbs - 1.0) * 0.6) + 1.0 else 1.0
                autosensResult = AutosensResult(
                    ratio = tddRatio / carbsRatio,
                    ratioFromTdd = tddRatio,
                    ratioFromCarbs = carbsRatio
                )
        } else {
            if (constraintsChecker.isAutosensModeEnabled().value()) {
                val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
                if (autosensData == null) {
                    rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                    return
                }
                autosensResult = autosensData.autosensResult
            } else autosensResult.sensResult = "autosens disabled"
        }

        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        /*
        ** Tsunami specific variables
        */
        // Get peak time if using a PK insulin model
        val insulin = activePlugin.activeInsulin
        val activityPredTimePK = insulin.peak //MP act. pred. time for PK ins. models; target time = insulin peak time

        // Get the ID of the currently used insulin preset
        val insulinID = insulin.id.value

        // wave active hours redefinition (allowing end times < start times)
        var waveStart : Double = preferences.get(DoubleKey.WaveStart)
        var waveEnd : Double = preferences.get(DoubleKey.WaveEnd)
        var referenceTimer: Double = MidnightUtils.secondsFromMidnight() / 3600.0 //MP current time in hours
        if (waveEnd < waveStart) {
            if (referenceTimer < waveStart) {
                referenceTimer -= (waveStart - 24.0) //MP Transformed timer, counting from (24 - waveStart) until 23;
            } else {
                referenceTimer -= waveStart //MP Transformed timer, counting from 0 until (23 - waveStart);
            }
            waveEnd = 24.0 - (waveStart - waveEnd) //MP transformed end hour, represents total duration of Tsunami in h
            waveStart = 0.0 //MP set starting hour to 0 and transform the rest;
        }

        // Determine which mode will be active (0 = default; 1 = wave; 2 = tsunami)
        val enableWave : Boolean = preferences.get(BooleanKey.EnableWave)
        val tsunamiModeID = persistenceLayer.getTsunamiActiveAt(now)?.tsunamiMode ?: if (referenceTimer in waveStart..waveEnd && enableWave) {
            1 //MP Tsunami inactive but conditions for wave are met --> use Wave
        } else {
            0 //MP Tsunami inactive and conditions for wave are not met --> use oref1
        }

        // Set mode specific variables to be relayed into DetermineBasalTsunami.kt
        var SMBcap : Double = 0.0
        var insulinReqPCT : Double = 0.0
        var activityTarget : Double = 0.0
        var deltaReductionPCT : Double = 0.0
        var deltaScore: Double = 0.5
        //TODO: Improve meal detection system!
        //TODO: Adjusted deltaScore divisor from 4 to 6 --> check performance (Oct 2024)
        if (tsunamiModeID == 2) { //MP: 2 = Tsunami
            deltaReductionPCT = 1.0
            SMBcap = preferences.get(DoubleKey.TsuSMBCap) //MP: User-set max SMB size for Tsunami.
            if (preferences.get(BooleanKey.TsuSMBscaling)) {
                SMBcap = (SMBcap * Math.min(profile.percentage.toDouble(), 130.0)/100.0) //SMBcap grows and shrinks with profile percentage;
            }
            insulinReqPCT = preferences.get(IntKey.TsuInsReqPCT).toDouble() / 100.0 // User-set percentage to modify insulin required
            activityTarget = preferences.get(IntKey.TsuActivityTarget).toDouble() / 100.0 // MP for small deltas
            deltaScore = Round.roundTo(glucoseStatus.shortAvgDelta/preferences.get(IntKey.TsuDeltaScoreDivisor), 0.01) //MP ShortAvgDelta must equal the divisor for deltaScore to be 1.0 (full force)
        } else if (tsunamiModeID == 1) { //MP: 1 = Wave
            deltaReductionPCT = 0.5
            SMBcap = preferences.get(DoubleKey.WaveSMBCap) ?: 0.0 //MP: User-set may SMB size for Wave.
            if (preferences.get(BooleanKey.WaveSMBCapScaling)) {
                SMBcap = (SMBcap * Math.min(profile.percentage.toDouble(), 130.0)/100.0) //SMBcap grows and shrinks with profile percentage;
            }
            insulinReqPCT = preferences.get(IntKey.WaveInsReqPCT).toDouble() / 100.0 // User-set percentage to modify insulin required
            activityTarget = preferences.get(IntKey.WaveActivityTarget).toDouble() / 100.0 // MP for small deltas
            deltaScore = Round.roundTo(glucoseStatus.shortAvgDelta/preferences.get(IntKey.WaveDeltaScoreDivisor), 0.01) //MP ShortAvgDelta must equal the divisor for deltaScore to be 1.0 (full force)
        }
        // Calculate reference activity values
        var currentActivity = 0.0
        for (i in -4..0) { //MP: -4 to 0 calculates all the insulin active during the last 5 minutes
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() - T.mins(i.toLong()).msecs(), profile) //TimeUnit.MINUTES.toMillis(i.toLong())
            currentActivity += iob.activity
        }

        var futureActivity = 0.0
        val activityPredTime: Long
        val activityPredTimePD = 65L //MP activity prediction time for pharmacodynamic model; fixed to 65 min (approx. peak time of 1 U bolus)
        if (insulinID != 105 && insulinID != 205) { //MP if not using PD insulin models
            activityPredTime = activityPredTimePK.toLong()
        } else { //MP if using PD insulin models
            activityPredTime = activityPredTimePD
        }
        for (i in -4..0) { //MP: calculate 5-minute-insulin activity centering around peakTime
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + T.mins(activityPredTime - i).msecs(), profile) //TimeUnit.MINUTES.toMillis(activityPredTime - i)
            futureActivity += iob.activity
        }

        val sensorLag = -10L //MP Assume that the glucose value measurement reflect the BG value from 'sensorlag' minutes ago & calculate the insulin activity then
        var sensorLagActivity = 0.0
        for (i in -4..0) {
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + T.mins(sensorLag - i).msecs(), profile) //TimeUnit.MINUTES.toMillis(sensorLag - i)
            sensorLagActivity += iob.activity
        }

        val activityHistoric = -20L //MP Activity at the time in minutes from now. Used to calculate activity in the past to use as target activity.
        var historicActivity = 0.0
        for (i in -2..2) {
            val iob = iobCobCalculator.calculateFromTreatmentsAndTemps(System.currentTimeMillis() + T.mins(activityHistoric - i).msecs(), profile) //TimeUnit.MINUTES.toMillis(activityHistoric - i)
            historicActivity += iob.activity
        }

        futureActivity = Round.roundTo(futureActivity, 0.0001)
        sensorLagActivity = Round.roundTo(sensorLagActivity, 0.0001)
        historicActivity = Round.roundTo(historicActivity, 0.0001)
        currentActivity = Round.roundTo(currentActivity, 0.0001)

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfileTsunami(
            dia = insulin.dia, //MP alternatively: profile.dia, but deprecated...
            min_5m_carbimpact = 0.0, // not used
            max_iob = constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSSMBPlugin"),
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = if (dynIsfMode) dynIsfResult.variableSensitivity ?: 0.0 else 0.0,
            insulinDivisor = dynIsfResult.insulinDivisor,
            TDD = dynIsfResult.tdd ?: 0.0,
            tsunamiModeID = tsunamiModeID,
            peakTime = activityPredTimePK.toDouble(),
            insulinID = insulinID,
            //tsuSMBCap = preferences.get(DoubleKey.TsuSMBCap),
            //tsuInsReqPCT = preferences.get(IntKey.TsuInsReqPCT),
            percentage = profile.value.originalPercentage,//profile.percentage,
            //tsunamiActive: Boolean,
            enableWaveMode = enableWave,
            waveStart = waveStart,
            waveEnd = waveEnd,
            referenceTimer = referenceTimer,
            waveUseSMBCap = preferences.get(BooleanKey.WaveUseSMBCap),
            SMBcap = SMBcap,
            insulinReqPCT = insulinReqPCT,
            activityTarget = activityTarget,
            deltaReductionPCT = deltaReductionPCT,
            //waveSMBCap = preferences.get(DoubleKey.WaveSMBCap),
            //waveInsReqPCT = preferences.get(IntKey.WaveInsReqPCT),
            //tsuSMBCapScaling = preferences.get(BooleanKey.TsuSMBscaling),
            //tsuActivityTarget = preferences.get(IntKey.TsuActivityTarget),
            //waveSMBCapScaling = preferences.get(BooleanKey.WaveSMBCapScaling),
            //waveActivityTarget = preferences.get(IntKey.WaveActivityTarget),
            futureActivity = futureActivity,
            activityPredTime = activityPredTime.toDouble(),
            sensorLagActivity = sensorLagActivity,
            historicActivity = historicActivity,
            currentActivity = currentActivity,
            deltaScore = deltaScore,
            lastBolus = persistenceLayer.getNewestBolus()?.amount ?: 0.0
        )
        val microBolusAllowed = constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

        determineBasalTsunami.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = dynIsfMode && dynIsfResult.tddPartsCalculated()
        ).also {
            val determineBasalResult = DetermineBasalResult(injector, it)
            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.autosensResult = autosensResult
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileTsunami = oapsProfile
            determineBasalResult.mealData = mealData
            lastAPSResult = determineBasalResult
            lastAPSRun = now
            aapsLogger.debug(LTag.APS, "Result: $it")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null &&
            requiredKey != "tsunami_mode_settings" &&
            requiredKey != "key_advanced_tsunami" &&
            requiredKey != "wave_mode_settings" &&
            requiredKey != "key_advanced_wave" &&
            requiredKey != "absorption_smb_advanced"
        ) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "key_tsunami_settings"
            title = rh.gs(R.string.tsunami)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxBasal, dialogMessage = R.string.openapsma_max_basal_summary, title = R.string.openapsma_max_basal_title))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob, dialogMessage = R.string.openapssmb_max_iob_summary, title = R.string.openapssmb_max_iob_title))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "tsunami_mode_settings"
                title = rh.gs(R.string.tsunami_mode_settings_title)
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.TsuSMBCap, dialogMessage = R.string.tsunami_smbcap_summary, title = R.string.tsunami_smbcap_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.TsuSMBscaling, summary = R.string.tsu_SMB_scaling_summary, title = R.string.tsu_SMB_scaling_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.TsuButtonIncrement1, dialogMessage = R.string.tsunami_button_insulin_increment_button_message, title = R.string.tsunami_button_insulin_increment_1))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.TsuButtonIncrement2, dialogMessage = R.string.tsunami_button_insulin_increment_button_message, title = R.string.tsunami_button_insulin_increment_2))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.TsuButtonIncrement3, dialogMessage = R.string.tsunami_button_insulin_increment_button_message, title = R.string.tsunami_button_insulin_increment_3))
                addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.TsuDefaultDuration, dialogMessage = R.string.tsunami_default_duration_message, title = R.string.tsunami_default_duration_title))
                val advancedTsu = PreferenceCategory(context)
                addPreference(advancedTsu)
                advancedTsu.apply {
                    key = "key_advanced_tsunami"
                    title = rh.gs(R.string.advanced_tsunami_title)
                    addPreference(AdaptiveIntentPreference(ctx = context, intentKey = IntentKey.TsuWaveDisclaimer, summary = R.string.advanced_tsu_wave_disclaimer))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.TsuActivityTarget, dialogMessage = R.string.tsu_activity_target_summary, title = R.string.tsu_activity_target_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.TsuInsReqPCT, dialogMessage = R.string.insulinReqPCT_summary, summary = R.string.insulinReqPCT_summary, title = R.string.insulinReqPCT_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.TsuDeltaScoreDivisor, dialogMessage = R.string.tsu_deltascore_divisor_dialogue, title = R.string.tsu_deltascore_divisor_title))
                }
            })
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "wave_mode_settings"
                title = rh.gs(R.string.wave_mode_settings_title)
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.EnableWave, summary = R.string.enable_wave_mode_summary, title = R.string.enable_wave_mode_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.HideTsunamiButton, summary = R.string.tsu_hide_tsunami_button_summary, title = R.string.tsu_hide_tsunami_button_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.WaveStart, dialogMessage = R.string.wave_start_summary, title = R.string.wave_start_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.WaveEnd, dialogMessage = R.string.wave_end_summary, title = R.string.wave_end_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.WaveUseSMBCap, summary = R.string.use_wave_smbcap_summary, title = R.string.use_wave_smbcap_title))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.WaveSMBCap, dialogMessage = R.string.wave_smbcap_message, title = R.string.wave_smbcap_title))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.WaveSMBCapScaling, summary = R.string.wave_SMB_scaling_summary, title = R.string.wave_SMB_scaling_title))
                val advancedWave = PreferenceCategory(context)
                addPreference(advancedWave)
                advancedWave.apply {
                    key = "key_advanced_wave"
                    title = rh.gs(R.string.advanced_wave_title)
                    addPreference(AdaptiveIntentPreference(ctx = context, intentKey = IntentKey.TsuWaveDisclaimer, summary = R.string.advanced_tsu_wave_disclaimer))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.WaveActivityTarget, dialogMessage = R.string.wave_activity_target_summary, title = R.string.wave_activity_target_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.WaveInsReqPCT, dialogMessage = R.string.wave_insulinReqPCT_message, title = R.string.wave_insulinReqPCT_title))
                    addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.WaveDeltaScoreDivisor, dialogMessage = R.string.wave_deltascore_divisor_dialogue, title = R.string.wave_deltascore_divisor_title))
                }
            })
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseDynamicSensitivity, summary = R.string.use_dynamic_sensitivity_summary, title = R.string.use_dynamic_sensitivity_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseAutosens, title = R.string.openapsama_use_autosens))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsDynIsfAdjustmentFactor, dialogMessage = R.string.dyn_isf_adjust_summary, title = R.string.dyn_isf_adjust_title))
            addPreference(AdaptiveUnitPreference(ctx = context, unitKey = UnitDoubleKey.ApsLgsThreshold, dialogMessage = R.string.lgs_threshold_summary, title = R.string.lgs_threshold_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsDynIsfAdjustSensitivity, summary = R.string.dynisf_adjust_sensitivity_summary, title = R.string.dynisf_adjust_sensitivity))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsSensitivityRaisesTarget, summary = R.string.sensitivity_raises_target_summary, title = R.string.sensitivity_raises_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsResistanceLowersTarget, summary = R.string.resistance_lowers_target_summary, title = R.string.resistance_lowers_target_title))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmb, summary = R.string.enable_smb_summary, title = R.string.enable_smb))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithHighTt, summary = R.string.enable_smb_with_high_temp_target_summary, title = R.string.enable_smb_with_high_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAlways, summary = R.string.enable_smb_always_summary, title = R.string.enable_smb_always))
            //Below missing
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithCob, summary = R.string.enable_smb_with_cob_summary, title = R.string.enable_smb_with_cob))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbWithLowTt, summary = R.string.enable_smb_with_temp_target_summary, title = R.string.enable_smb_with_temp_target))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseSmbAfterCarbs, summary = R.string.enable_smb_after_carbs_summary, title = R.string.enable_smb_after_carbs))
            //
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxSmbFrequency, title = R.string.smb_interval_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsMaxMinutesOfBasalToLimitSmb, title = R.string.smb_max_minutes_summary))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsUamMaxMinutesOfBasalToLimitSmb, dialogMessage = R.string.uam_smb_max_minutes, title = R.string.uam_smb_max_minutes_summary))
            addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsUseUam, summary = R.string.enable_uam_summary, title = R.string.enable_uam))
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.ApsCarbsRequestThreshold, dialogMessage = R.string.carbs_req_threshold_summary, title = R.string.carbs_req_threshold))
            addPreference(preferenceManager.createPreferenceScreen(context).apply {
                key = "absorption_smb_advanced"
                title = rh.gs(app.aaps.core.ui.R.string.advanced_settings_title)
                addPreference(AdaptiveIntentPreference(ctx = context, intentKey = IntentKey.ApsLinkToDocs, intent = Intent().apply { action = Intent.ACTION_VIEW; data = Uri.parse(rh.gs(R.string.openapsama_link_to_preference_json_doc)) }, summary = R.string.openapsama_link_to_preference_json_doc_txt))
                addPreference(AdaptiveSwitchPreference(ctx = context, booleanKey = BooleanKey.ApsAlwaysUseShortDeltas, summary = R.string.always_use_short_avg_summary, title = R.string.always_use_short_avg))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxDailyMultiplier, dialogMessage = R.string.openapsama_max_daily_safety_multiplier_summary, title = R.string.openapsama_max_daily_safety_multiplier))
                addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.ApsMaxCurrentBasalMultiplier, dialogMessage = R.string.openapsama_current_basal_safety_multiplier_summary, title = R.string.openapsama_current_basal_safety_multiplier))
            })
        }
    }
}