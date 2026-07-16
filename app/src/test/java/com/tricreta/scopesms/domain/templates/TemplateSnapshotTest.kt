package com.tricreta.scopesms.domain.templates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Templates ship with defaults; pricing rules deliberately don't. This covers
 * the overlay that makes "no row stored" mean "still using ours" without a
 * seeded database.
 */
class TemplateSnapshotTest {

    @Test
    fun `a fresh install has both templates from the defaults`() {
        val snapshot = TemplateSnapshot.from(emptyList())

        assertThat(snapshot.forType(TemplateType.UNMATCHED).body).isEqualTo(DefaultTemplates.UNMATCHED)
        assertThat(snapshot.forType(TemplateType.MATCHED).body).isEqualTo(DefaultTemplates.MATCHED)
        assertThat(snapshot.isAllDefault).isTrue()
    }

    @Test
    fun `a stored template overrides its default`() {
        val snapshot = TemplateSnapshot.from(
            listOf(MessageTemplate(TemplateType.MATCHED, "Asante {name}!", isDefault = false)),
        )

        assertThat(snapshot.forType(TemplateType.MATCHED).body).isEqualTo("Asante {name}!")
        assertThat(snapshot.forType(TemplateType.MATCHED).isDefault).isFalse()
    }

    @Test
    fun `customising one flow leaves the other on its default`() {
        val snapshot = TemplateSnapshot.from(
            listOf(MessageTemplate(TemplateType.MATCHED, "Asante {name}!", isDefault = false)),
        )

        assertThat(snapshot.forType(TemplateType.UNMATCHED).body).isEqualTo(DefaultTemplates.UNMATCHED)
        assertThat(snapshot.forType(TemplateType.UNMATCHED).isDefault).isTrue()
        assertThat(snapshot.isAllDefault).isFalse()
    }

    @Test
    fun `every flow always resolves to a template`() {
        // forType is total by construction — the decide path can never be handed
        // a null and left improvising a message to a paying customer.
        val snapshot = TemplateSnapshot.from(emptyList())

        TemplateType.entries.forEach { type ->
            assertThat(snapshot.forType(type).body).isNotEmpty()
        }
    }

    @Test
    fun `the shared DEFAULTS instance matches an empty load`() {
        assertThat(TemplateSnapshot.DEFAULTS.forType(TemplateType.UNMATCHED).body)
            .isEqualTo(DefaultTemplates.UNMATCHED)
    }
}
