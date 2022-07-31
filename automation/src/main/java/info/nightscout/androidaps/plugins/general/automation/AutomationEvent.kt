package info.nightscout.androidaps.plugins.general.automation

import dagger.android.HasAndroidInjector
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.androidaps.plugins.general.automation.actions.Action
import info.nightscout.androidaps.plugins.general.automation.actions.ActionDummy
import info.nightscout.androidaps.plugins.general.automation.actions.ActionStopProcessing
import info.nightscout.androidaps.plugins.general.automation.triggers.TriggerConnector
import info.nightscout.androidaps.plugins.general.automation.triggers.TriggerDummy
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.T
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import javax.inject.Inject

class AutomationEvent(private val injector: HasAndroidInjector) {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var dateUtil: DateUtil

    var title: String = ""
    var isEnabled = true
    var position = -1
    var systemAction: Boolean = false // true = generated by AAPS, false = entered by user
    var readOnly: Boolean = false // removing, editing disabled
    var autoRemove: Boolean = false // auto-remove once used
    var userAction: Boolean = false // shows button on Overview

    var trigger: TriggerConnector = TriggerConnector(injector)
    val actions: MutableList<Action> = ArrayList()

    var lastRun: Long = 0

    init {
        injector.androidInjector().inject(this)
    }

    fun getPreconditions(): TriggerConnector {
        val trigger = TriggerConnector(injector, TriggerConnector.Type.AND)
        for (action in actions) {
            action.precondition?.let { trigger.list.add(it) }
        }
        return trigger
    }

    fun addAction(action: Action) = actions.add(action)

    fun areActionsValid(): Boolean {
        var result = true
        for (action in actions) result = result && action.isValid()
        if (!result) isEnabled = false
        return result
    }

    fun hasStopProcessing(): Boolean {
        for (action in actions) if (action is ActionStopProcessing) return true
        return false
    }

    fun toJSON(): String {
        val array = JSONArray()
        for (a in actions) array.put(a.toJSON())
        return JSONObject()
            .put("title", title)
            .put("enabled", isEnabled)
            .put("systemAction", systemAction)
            .put("readOnly", readOnly)
            .put("autoRemove", autoRemove)
            .put("userAction", userAction)
            .put("trigger", trigger.toJSON())
            .put("actions", array)
            .toString()
    }

    fun fromJSON(data: String, position: Int): AutomationEvent {
        val d = JSONObject(data)
        title = d.optString("title", "")
        isEnabled = d.optBoolean("enabled", true)
        systemAction = d.optBoolean("systemAction", false)
        this.position = position
        readOnly = d.optBoolean("readOnly", false)
        autoRemove = d.optBoolean("autoRemove", false)
        userAction = d.optBoolean("userAction", false)
        trigger = TriggerDummy(injector).instantiate(JSONObject(d.getString("trigger"))) as TriggerConnector
        val array = d.getJSONArray("actions")
        actions.clear()
        for (i in 0 until array.length()) {
            ActionDummy(injector).instantiate(JSONObject(array.getString(i)))?.let {
                actions.add(it)
            }
        }
        return this
    }

    fun shouldRun(): Boolean {
        return lastRun <= dateUtil.now() - T.mins(5).msecs()
    }
}
