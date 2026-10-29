package cn.yukehelper.android

import android.content.Context
import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.LinearLayout
import cn.yukehelper.core.*

// Internal Kotlin-only view with required callbacks; never inflated from XML.
@SuppressLint("ViewConstructor")
internal class CourseIndexView(
    context: Context,
    onRefresh: () -> Unit,
    onSwitch: () -> Unit,
    private val onApply: (String, Set<String>, Set<String>) -> Unit,
) : LinearLayout(context) {
    private data class Draft(var baseline: Set<String>, var selected: Set<String>, var query: String = "")
    private val drafts = mutableMapOf<String, Draft>()
    private var accountKey: String? = null
    private var courses: List<Course> = emptyList()
    private var busy = false
    private val accountLabel = context.label("", 12f, Palette.muted)
    private val message = context.label("", 12f, Palette.warning)
    private val hint = context.label("选择后点击“应用课程选择”。", 12f, Palette.muted)
    private val switch = context.compactAction("切换账号", onSwitch)
    private val refresh = context.compactAction("刷新", onRefresh)
    private val apply = context.action("应用课程选择") {
        val key = accountKey ?: return@action
        val draft = drafts[key] ?: return@action
        onApply(key, courses.map { it.id }.toSet(), draft.selected.toSet())
    }
    private val checklist = CourseChecklist(context) { selected ->
        accountKey?.let { drafts[it]?.selected = selected }
        updateApply()
    }
    init {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        val heading = context.row()
        heading.addView(context.label("我的课程", 23f, bold = true), LayoutParams(0, -2, 1f))
        heading.addView(switch, LayoutParams(context.dp(92), -2).apply { marginEnd = context.dp(5) })
        heading.addView(refresh, LayoutParams(context.dp(60), -2))
        addView(heading)
        block(accountLabel, 5)
        block(hint, 8)
        block(message, 6)
        addView(checklist, LayoutParams(-1, 0, 1f))
        addView(apply, context.touchParams().apply { topMargin = context.dp(8) })
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val short = MeasureSpec.getSize(heightMeasureSpec) < context.dp(420)
        accountLabel.visibility = if (short) GONE else VISIBLE
        hint.visibility = if (short) GONE else VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun bind(state: AppState) {
        val p = state.profile ?: return
        val key = p.account.key
        if (accountKey != key) {
            accountKey?.let { drafts[it]?.query = checklist.query }
        }
        val valid = p.courses.map { it.id }.toSet()
        val server = p.courses.filter { p.selects(it.id) }.map { it.id }.toSet()
        val draft = drafts.getOrPut(key) { Draft(server, server) }
        val pendingOn = draft.selected - draft.baseline
        val pendingOff = draft.baseline - draft.selected
        draft.selected = (server + pendingOn - pendingOff).intersect(valid)
        draft.baseline = server
        val accountChanged = accountKey != key
        accountKey = key; courses = p.courses
        checklist.bind(courses, draft.selected, state.lessons.map { it.classroomId }.toSet())
        if (accountChanged) checklist.query = draft.query
        accountLabel.text = context.getString(R.string.course_account, p.account.name, if (p.account.demo) "演示账号" else Domain.requireHost(p.account.domain).label)
        switch.visibility = if (state.configuration.profiles.size > 1) VISIBLE else GONE
        refresh.isEnabled = !state.loading; refresh.text = if (state.loading) "刷新中" else "刷新"
        message.text = state.message
        message.visibility = if (state.message.isBlank()) GONE else VISIBLE
        updateApply()
    }
    fun saving(value: Boolean) { busy = value; updateApply() }
    private fun updateApply() {
        val draft = accountKey?.let(drafts::get) ?: return
        val dirty = draft.selected != draft.baseline
        apply.isEnabled = !busy && dirty
        apply.text = when { busy -> "正在保存…"; dirty -> "应用课程选择（${draft.selected.size} 门）"; else -> "已应用 · ${draft.selected.size} 门课程" }
    }
    fun saveState(): Bundle {
        accountKey?.let { drafts[it]?.query = checklist.query }
        return Bundle().apply {
            putStringArrayList("keys", ArrayList(drafts.keys))
            drafts.forEach { (key, draft) -> putBundle(key, Bundle().apply {
                putStringArrayList("baseline", ArrayList(draft.baseline)); putStringArrayList("selected", ArrayList(draft.selected)); putString("query", draft.query)
            }) }
        }
    }
    fun restoreState(saved: Bundle?) {
        saved?.getStringArrayList("keys")?.forEach { key -> saved.getBundle(key)?.let {
            drafts[key] = Draft(it.getStringArrayList("baseline").orEmpty().toSet(), it.getStringArrayList("selected").orEmpty().toSet(), it.getString("query").orEmpty())
        } }
    }
}
