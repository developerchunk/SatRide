package com.developerstring.satride.feature.play.multiplay.ui

import com.developerstring.satride.feature.play.multiplay.engine.GameMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules card's content.
 *
 * Not a rendering test — a coverage one. The failure this guards against is a mode being added
 * and quietly shipping with no explanation, which is the state the whole game was in before this
 * card existed.
 */
class HowToPlayTest {

    @Test
    fun everyModeHasAGoalAndControls() {
        GameMode.entries.forEach { mode ->
            val guide = howToPlayFor(mode, motionAvailable = true)

            assertTrue(guide.goal.isNotBlank(), "${mode.id} has no goal")
            assertTrue(guide.tip.isNotBlank(), "${mode.id} has no tip")
            assertTrue(
                guide.controls.size == 3,
                "${mode.id} explains ${guide.controls.size} controls; three is the number a player can hold",
            )
            guide.controls.forEach { control ->
                assertTrue(control.name.isNotBlank(), "${mode.id} has an unnamed control")
                assertTrue(control.what.isNotBlank(), "${mode.id}'s ${control.name} does not say what it does")
            }
        }
    }

    /**
     * Steering is explained first, every time.
     *
     * It is the one control a player cannot do without, and the one they could not previously find
     * at all — so it leads whatever the mode.
     */
    @Test
    fun steeringIsAlwaysTheFirstControlExplained() {
        GameMode.entries.forEach { mode ->
            assertEquals(
                "Stick",
                howToPlayFor(mode, motionAvailable = true).controls.first().name,
                "${mode.id} explains something before it explains how to steer",
            )
        }
    }

    /** Touch and keyboard players are told about their own controls, not each other's. */
    @Test
    fun theSteeringHintFollowsTheDevice() {
        val touch = howToPlayFor(GameMode.RACE, motionAvailable = true).controls.first().what
        val keyboard = howToPlayFor(GameMode.RACE, motionAvailable = false).controls.first().what

        assertTrue(touch.contains("pad", ignoreCase = true), "the touch hint does not mention the pad")
        assertTrue(keyboard.contains("arrow", ignoreCase = true), "the desktop hint does not mention the keys")
    }

    /** Each mode's goal is its own; a copy-paste would make the card useless for three of the four. */
    @Test
    fun theGoalsAreDistinct() {
        val goals = GameMode.entries.map { howToPlayFor(it, motionAvailable = true).goal }

        assertEquals(goals.size, goals.toSet().size, "two modes share a goal")
    }
}
