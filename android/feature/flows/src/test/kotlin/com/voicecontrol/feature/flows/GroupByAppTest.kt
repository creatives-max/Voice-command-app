package com.voicecontrol.feature.flows

import com.voicecontrol.core.model.FlowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupByAppTest {
    private fun flow(id: String, pkg: String, t: Long) = FlowDefinition(id, 1, pkg, id, "sig", emptyList(), t)

    @Test
    fun `apps are ordered by latest change and flows newest first`() {
        val groups = groupByApp(listOf(flow("a", "com.a", 1), flow("b", "com.b", 5), flow("c", "com.a", 9)))
        assertEquals(listOf("com.a", "com.b"), groups.map { it.appPackage })
        assertEquals(listOf("c", "a"), groups[0].flows.map { it.id })
    }
}
