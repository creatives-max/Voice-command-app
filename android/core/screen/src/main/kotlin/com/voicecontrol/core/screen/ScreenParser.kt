package com.voicecontrol.core.screen

import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot

/**
 * Turns an accessibility node tree into a [ScreenSnapshot] of fields and buttons.
 *
 * Steps:
 * 1. Walk the visible tree, collecting interactive candidates and static text nodes.
 * 2. Resolve a human label for each candidate: hint → labelFor → content description →
 *    own/child text → nearest text above / to the left → humanized view id.
 * 3. Classify the field type, mask sensitive values and assign stable IDs.
 * 4. Sort elements into reading order and compute the screen signature.
 */
class ScreenParser(
    private val stableIds: StableIdGenerator = StableIdGenerator(),
    private val maxLabelDistancePx: Int = 260,
) {

    private data class TextNode(val text: String, val bounds: Bounds, val order: Int)

    private data class Candidate(
        val node: UiNode,
        val kind: ElementKind,
        val path: String,
        val order: Int,
        /** Text found inside this clickable container (for buttons without own text). */
        val innerText: String?,
    )

    fun parse(
        root: UiNode?,
        packageName: String,
        activityName: String? = null,
        capturedAtMillis: Long = 0L,
    ): ScreenSnapshot = parseWithNodes(root, packageName, activityName, capturedAtMillis).snapshot

    /** Like [parse] but also returns the node for every element id (used by the action executor). */
    @Suppress("UNCHECKED_CAST")
    fun <N : UiNode> parseWithNodes(
        root: N?,
        packageName: String,
        activityName: String? = null,
        capturedAtMillis: Long = 0L,
    ): ParseResult<N> {
        if (root == null) return ParseResult(ScreenSnapshot.empty(packageName), emptyMap())

        val candidates = mutableListOf<Candidate>()
        val texts = mutableListOf<TextNode>()
        var order = 0
        var anyScrollable = false
        var title: String? = LabelText.clean(root.paneTitle)

        fun visit(node: UiNode, path: String, insideCandidate: Boolean) {
            if (!node.isVisibleToUser) return
            if (node.isScrollable) anyScrollable = true
            if (title == null) title = LabelText.clean(node.paneTitle)

            val kind = kindOf(node)
            val isCandidate = kind != null && !insideCandidate
            if (isCandidate) {
                val inner = if (kind == ElementKind.TEXT_FIELD) null else collectInnerText(node)
                candidates += Candidate(node, kind!!, path, order++, inner)
            } else if (!node.isEditable && !insideCandidate) {
                val t = LabelText.clean(node.text) ?: LabelText.clean(node.contentDescription)
                if (t != null && !node.boundsInScreen.isEmpty) texts += TextNode(t, node.boundsInScreen, order++)
            }
            node.children.forEachIndexed { index, child ->
                // Editable fields can contain their own children (e.g. Compose text field decorations);
                // text inside a clickable button belongs to the button, not to the free text pool.
                visit(child, "$path.$index", insideCandidate || (isCandidate && kind != ElementKind.TEXT_FIELD))
            }
        }
        visit(root, "0", insideCandidate = false)

        val consumedTexts = mutableSetOf<TextNode>()
        val drafts = candidates.map { candidate ->
            val label = resolveLabel(candidate, texts, consumedTexts)
            Draft(candidate, label)
        }

        val ids = stableIds.assign(
            drafts.map { StableIdGenerator.Input(it.candidate.node.viewIdResourceName, it.candidate.kind, it.label, it.candidate.path) },
        )

        val nodesById = HashMap<String, N>(drafts.size)
        val elements = drafts.mapIndexed { index, draft ->
            nodesById[ids[index]] = draft.candidate.node as N
            toElement(draft, ids[index])
        }.sortedWith(compareBy({ it.bounds.top / ROW_TOLERANCE_PX }, { it.bounds.left }))

        val snapshot = ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            title = title,
            elements = elements,
            isScrollable = anyScrollable,
            capturedAtMillis = capturedAtMillis,
        )
        return ParseResult(snapshot.copy(signature = ScreenSignature.of(snapshot)), nodesById)
    }

    private data class Draft(val candidate: Candidate, val label: String)

    private fun toElement(draft: Draft, id: String): ScreenElement {
        val node = draft.candidate.node
        val kind = draft.candidate.kind
        val fieldType = if (kind == ElementKind.TEXT_FIELD) FieldClassifier.classify(node, draft.label) else null
        val sensitive = node.isPassword || fieldType?.isSensitive == true
        val currentValue = when {
            sensitive -> null
            kind != ElementKind.TEXT_FIELD && kind != ElementKind.DROPDOWN -> null
            node.isShowingHintText -> null
            else -> LabelText.clean(node.text)?.takeIf { it != LabelText.clean(node.hintText) }
        }
        return ScreenElement(
            id = id,
            kind = kind,
            label = draft.label,
            fieldType = fieldType,
            hint = LabelText.clean(node.hintText),
            value = currentValue,
            isSensitive = sensitive,
            isEnabled = node.isEnabled,
            isFocused = node.isFocused,
            isChecked = if (kind.isToggle) node.isChecked else null,
            bounds = node.boundsInScreen,
            viewId = node.viewIdResourceName,
            className = node.className,
            maxLength = node.maxTextLength.takeIf { it > 0 },
        )
    }

    private fun kindOf(node: UiNode): ElementKind? {
        val cls = node.className.orEmpty()
        return when {
            node.isEditable -> ElementKind.TEXT_FIELD
            cls.endsWith("EditText") || cls.endsWith("AutoCompleteTextView") -> ElementKind.TEXT_FIELD
            cls.endsWith("Spinner") -> ElementKind.DROPDOWN
            node.isCheckable && (cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("SwitchMaterial")) -> ElementKind.SWITCH
            node.isCheckable && cls.endsWith("RadioButton") -> ElementKind.RADIO
            node.isCheckable -> ElementKind.CHECKBOX
            node.isClickable && node.isEnabled && (cls.endsWith("Button") || cls.endsWith("ImageButton")) -> ElementKind.BUTTON
            node.isClickable && node.isEnabled && hasAnyText(node) -> ElementKind.BUTTON
            else -> null
        }
    }

    private fun hasAnyText(node: UiNode): Boolean =
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
            node.children.any { it.isVisibleToUser && !it.isEditable && hasAnyText(it) }

    private fun collectInnerText(node: UiNode): String? {
        val parts = mutableListOf<String>()
        fun walk(n: UiNode) {
            if (!n.isVisibleToUser) return
            LabelText.clean(n.text)?.let { parts += it } ?: LabelText.clean(n.contentDescription)?.let { parts += it }
            n.children.forEach(::walk)
        }
        node.children.forEach(::walk)
        return parts.distinct().joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun resolveLabel(candidate: Candidate, texts: List<TextNode>, consumed: MutableSet<TextNode>): String {
        val node = candidate.node
        val own = when (candidate.kind) {
            // For an editable field `text` is the *value*, so it is only a label fallback via hint.
            ElementKind.TEXT_FIELD -> listOf(
                node.hintText,
                node.labeledBy?.text,
                node.labeledBy?.contentDescription,
                node.contentDescription,
                node.tooltipText,
                node.text.takeIf { node.isShowingHintText },
            )
            else -> listOf(node.text, node.contentDescription, candidate.innerText, node.tooltipText, node.labeledBy?.text)
        }.firstNotNullOfOrNull { LabelText.clean(it) }

        // A generic hint such as "Type here" is worse than a real caption printed above the field.
        val ownIsGenericHint = own != null && candidate.kind == ElementKind.TEXT_FIELD &&
            own == LabelText.clean(node.hintText) && LabelText.normalize(own) in genericHints
        val nearby = if (own == null || ownIsGenericHint) nearestLabel(node.boundsInScreen, texts, consumed) else null
        if (nearby != null) consumed += nearby

        return (if (ownIsGenericHint) nearby?.text ?: own else own)
            ?: nearby?.text
            ?: LabelText.humanizeViewId(node.viewIdResourceName)
            ?: defaultLabel(candidate.kind)
    }

    /** Closest unconsumed text node directly above (overlapping horizontally) or on the left on the same row. */
    private fun nearestLabel(field: Bounds, texts: List<TextNode>, consumed: Set<TextNode>): TextNode? {
        if (field.isEmpty) return null
        return texts.asSequence()
            .filter { it !in consumed }
            .mapNotNull { t ->
                val above = t.bounds.bottom <= field.top + ROW_TOLERANCE_PX && t.bounds.horizontallyOverlaps(field)
                val leftSameRow = t.bounds.right <= field.left + ROW_TOLERANCE_PX && t.bounds.verticallyOverlaps(field)
                val distance = when {
                    leftSameRow -> field.left - t.bounds.right
                    above -> field.top - t.bounds.bottom
                    else -> return@mapNotNull null
                }
                if (distance in -ROW_TOLERANCE_PX..maxLabelDistancePx) t to distance else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun defaultLabel(kind: ElementKind): String = when (kind) {
        ElementKind.TEXT_FIELD -> "Text field"
        ElementKind.BUTTON -> "Button"
        ElementKind.CHECKBOX -> "Checkbox"
        ElementKind.SWITCH -> "Switch"
        ElementKind.RADIO -> "Option"
        ElementKind.DROPDOWN -> "Dropdown"
        ElementKind.LINK -> "Link"
    }

    companion object {
        const val ROW_TOLERANCE_PX = 24
        private val genericHints = setOf("type here", "enter here", "enter", "required", "optional", "enter value", "yahan likhen", "यहां लिखें")
    }
}
