package com.cosmotune

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks the reader that [ForkGuardTest.workflowsOnlyRunWhenStartedByHand] uses on the files in
 * `.github/workflows`, with small workflows written out here. If this reader were wrong, that guard could go green for a
 * workflow that starts on its own.
 */
class ForkGuardTriggerParserTest {

    /** The names of the triggers that are reported (the explanation after " (" is left out). */
    private fun reported(yaml: String) = forbiddenWorkflowTriggers(yaml.trimIndent()).map { it.substringBefore(" (") }

    private fun assertAllowed(yaml: String) = assertEquals(emptyList(), reported(yaml))

    private fun assertReported(expected: List<String>, yaml: String) = assertEquals(expected, reported(yaml))

    @Test
    fun releaseShapeIsAllowed() = assertAllowed(
        """
        name: Release
        on:
          push:
            tags: ["v*.*.*"]
          workflow_dispatch:
            inputs:
              version:
                description: "x"
                required: true

        permissions:
          contents: write
        jobs:
          a: {}
        """,
    )

    @Test
    fun pushToBranchesIsReported() = assertReported(
        listOf("push"),
        """
        on:
          push:
            branches: [main]
          workflow_dispatch:
        jobs: {}
        """,
    )

    @Test
    fun pushNeedsTagsAndNothingElse() {
        assertReported(listOf("push"), "on:\n  push:\n    tags: ['v*']\n    branches: [main]\njobs: {}")
        assertReported(listOf("push"), "on:\n  push:\n    tags: ['v*']\n    paths: ['a/**']\njobs: {}")
        assertReported(listOf("push"), "on:\n  push:\n    tags-ignore: ['v*']\njobs: {}")
        assertReported(listOf("push"), "on:\n  push:\njobs: {}")
    }

    @Test
    fun everyOtherTriggerIsReportedAndCommentsAreIgnored() = assertReported(
        listOf("pull_request", "schedule", "issue_comment"),
        """
        name: x
        on:   # when
          # pull_request in a comment only
          workflow_dispatch:
          pull_request:
          schedule:
            - cron: '0 0 * * *'
          issue_comment:
            types: [created]
        jobs: {}
        """,
    )

    @Test
    fun aTriggerNamedOnlyInACommentDoesNotCount() = assertAllowed(
        """
        on:
          workflow_dispatch: # not pull_request or schedule
        jobs: {}
        """,
    )

    @Test
    fun inlineForms() {
        assertAllowed("on: workflow_dispatch\njobs: {}")
        assertAllowed("on: [workflow_dispatch]\njobs: {}")
        assertReported(listOf("push", "pull_request"), "on: [push, pull_request]\njobs: {}")
        assertReported(listOf("push"), "on: push\njobs: {}")
    }

    @Test
    fun listForm() = assertReported(
        listOf("push", "release"),
        """
        on:
          - push
          - workflow_dispatch
          - release
        jobs: {}
        """,
    )

    @Test
    fun quotedKeysAreSeen() {
        assertReported(
            listOf("workflow_run", "release"),
            """
            "on":
              workflow_run:
                workflows: [A]
              release:
                types: [published]
            jobs: {}
            """,
        )
        assertReported(
            listOf("pull_request", "schedule"),
            "on:\n  \"pull_request\":\n  'schedule':\n    - cron: '0 0 * * *'\n  workflow_dispatch:\njobs: {}",
        )
    }

    @Test
    fun aWorkflowWithoutAnOnBlockIsReported() {
        assertEquals(1, forbiddenWorkflowTriggers("name: x\njobs: {}").size)
        assertEquals(1, forbiddenWorkflowTriggers("on:\njobs: {}").size)
    }

    @Test
    fun whatCannotBeReadIsReportedNotIgnored() {
        // a line at trigger level that is no trigger, and a line that belongs to no trigger
        assertEquals(1, forbiddenWorkflowTriggers("on:\n  workflow_dispatch:\n  this is not a trigger\njobs: {}").size)
        assertEquals(1, forbiddenWorkflowTriggers("on:\n    workflow_dispatch:\n  stray: 1\njobs: {}").size)
    }
}
