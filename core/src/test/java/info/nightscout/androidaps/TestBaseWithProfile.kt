package info.nightscout.androidaps

import android.content.Context
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.data.ProfileSealed
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.extensions.pureProfileFromJson
import info.nightscout.androidaps.interfaces.ActivePlugin
import info.nightscout.androidaps.interfaces.Config
import info.nightscout.androidaps.interfaces.Profile
import info.nightscout.androidaps.interfaces.ProfileFunction
import info.nightscout.androidaps.interfaces.ProfileStore
import info.nightscout.androidaps.interfaces.ResourceHelper
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DefaultValueHelper
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.HardLimits
import info.nightscout.shared.sharedPreferences.SP
import org.json.JSONObject
import org.junit.Before
import org.mockito.Mock
import org.mockito.Mockito

@Suppress("SpellCheckingInspection")
open class TestBaseWithProfile : TestBase() {

    @Mock lateinit var activePluginProvider: ActivePlugin
    @Mock lateinit var rh: ResourceHelper
    @Mock lateinit var fabricPrivacy: FabricPrivacy
    @Mock lateinit var profileFunction: ProfileFunction
    @Mock lateinit var defaultValueHelper: DefaultValueHelper
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var config: Config
    @Mock lateinit var sp: SP
    @Mock lateinit var context: Context
    @Mock lateinit var repository: AppRepository

    lateinit var testPumpPlugin: TestPumpPlugin

    val rxBus = RxBus(aapsSchedulers, aapsLogger)

    val profileInjector = HasAndroidInjector {
        AndroidInjector {
            if (it is ProfileStore) {
                it.aapsLogger = aapsLogger
                it.activePlugin = activePluginProvider
                it.config = config
                it.rh = rh
                it.rxBus = rxBus
                it.hardLimits = HardLimits(aapsLogger, rxBus, sp, rh, context, repository)
            }
        }
    }

    private lateinit var invalidProfileJSON: String
    private lateinit var validProfileJSON: String
    lateinit var validProfile: Profile
    lateinit var invalidProfile: Profile
    @Suppress("PropertyName") val TESTPROFILENAME = "someProfile"

    @Before
    fun prepareMock() {
        invalidProfileJSON = "{\"dia\":\"1\",\"carbratio\":[{\"time\":\"00:00\",\"value\":\"30\"}],\"carbs_hr\":\"20\",\"delay\":\"20\",\"sens\":[{\"time\":\"00:00\",\"value\":\"3\"}," +
            "{\"time\":\"2:00\",\"value\":\"3.4\"}],\"timezone\":\"UTC\",\"basal\":[{\"time\":\"00:00\",\"value\":\"1\"}],\"target_low\":[{\"time\":\"00:00\",\"value\":\"4.5\"}]," +
            "\"target_high\":[{\"time\":\"00:00\",\"value\":\"7\"}],\"startDate\":\"1970-01-01T00:00:00.000Z\",\"units\":\"mmol\"}"
        validProfileJSON = "{\"dia\":\"5\",\"carbratio\":[{\"time\":\"00:00\",\"value\":\"30\"}],\"carbs_hr\":\"20\",\"delay\":\"20\",\"sens\":[{\"time\":\"00:00\",\"value\":\"3\"}," +
            "{\"time\":\"2:00\",\"value\":\"3.4\"}],\"timezone\":\"UTC\",\"basal\":[{\"time\":\"00:00\",\"value\":\"1\"}],\"target_low\":[{\"time\":\"00:00\",\"value\":\"4.5\"}]," +
            "\"target_high\":[{\"time\":\"00:00\",\"value\":\"7\"}],\"startDate\":\"1970-01-01T00:00:00.000Z\",\"units\":\"mmol\"}"
        validProfile = ProfileSealed.Pure(pureProfileFromJson(JSONObject(validProfileJSON), dateUtil)!!)
        testPumpPlugin = TestPumpPlugin(profileInjector)
        Mockito.`when`(activePluginProvider.activePump).thenReturn(testPumpPlugin)
    }

    fun getValidProfileStore(): ProfileStore {
        val json = JSONObject()
        val store = JSONObject()
        store.put(TESTPROFILENAME, JSONObject(validProfileJSON))
        json.put("defaultProfile", TESTPROFILENAME)
        json.put("store", store)
        return ProfileStore(profileInjector, json, dateUtil)
    }

    fun getInvalidProfileStore1(): ProfileStore {
        val json = JSONObject()
        val store = JSONObject()
        store.put(TESTPROFILENAME, JSONObject(invalidProfileJSON))
        json.put("defaultProfile", TESTPROFILENAME)
        json.put("store", store)
        return ProfileStore(profileInjector, json, dateUtil)
    }

    fun getInvalidProfileStore2(): ProfileStore {
        val json = JSONObject()
        val store = JSONObject()
        store.put(TESTPROFILENAME, JSONObject(validProfileJSON))
        store.put("invalid", JSONObject(invalidProfileJSON))
        json.put("defaultProfile", TESTPROFILENAME + "invalid")
        json.put("store", store)
        return ProfileStore(profileInjector, json, dateUtil)
    }
}
