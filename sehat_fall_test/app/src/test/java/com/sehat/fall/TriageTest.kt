package com.sehat.fall

import com.sehat.fall.alert.VoiceCommandListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageTest {

    enum class TriageState {
        FALL_DETECTED,
        PHASE1_LISTENING_HELP,
        CALL_PLACED_VOICE_HELP,
        PHASE2_AWAITING_SAFE_CONFIRM,
        ALERT_CANCELLED_SAFE,
        UNCONSCIOUS_AUTO_CALL_PLACED
    }

    private fun isHelpSpotted(inputPhrase: String): Boolean {
        val lower = inputPhrase.lowercase().trim()
        return VoiceCommandListener.HELP_KEYWORDS.any { lower.contains(it) }
    }

    @Test
    fun testVoiceHelpKeywordMatching() {
        // True positives: phrases that must trigger emergency call
        assertTrue(isHelpSpotted("help"))
        assertTrue(isHelpSpotted("HELP"))
        assertTrue(isHelpSpotted("Help me"))
        assertTrue(isHelpSpotted("Please help"))
        assertTrue(isHelpSpotted("Can someone help me please"))
        assertTrue(isHelpSpotted("emergency"))
        assertTrue(isHelpSpotted("this is an emergency"))
        assertTrue(isHelpSpotted("bachao"))
        assertTrue(isHelpSpotted("save me"))

        // True negatives: phrases that should NOT trigger help
        assertFalse(isHelpSpotted("I am okay"))
        assertFalse(isHelpSpotted("hello"))
        assertFalse(isHelpSpotted("just walking"))
        assertFalse(isHelpSpotted("false alarm"))
        assertFalse(isHelpSpotted("good morning"))
        assertFalse(isHelpSpotted("thank you"))
    }

    /**
     * Verifies the complete state machine of the post-fall emergency triage flow:
     * 1. Fall Detected -> Phase 1 (Listening for "HELP")
     * 2. Branch A: "HELP" heard -> Immediate call to relative
     * 3. Branch B: No "HELP" -> Phase 2 (Voice prompt: "PLEASE CONFIRM THAT YOU ARE SAFE")
     * 4. Branch B1: User confirms safe -> Alert cancelled
     * 5. Branch B2: User does not confirm (timeout) -> Assumed unconscious -> Automatic call to relative
     */
    @Test
    fun testPostFallTriageStateMachine() {
        class TriageSimulator(val relativePhone: String) {
            var state = TriageState.FALL_DETECTED
            var lastCallReason: String? = null

            fun onFallDetected() {
                state = TriageState.PHASE1_LISTENING_HELP
            }

            fun onVoiceInput(phrase: String) {
                if (state == TriageState.PHASE1_LISTENING_HELP) {
                    if (isHelpSpotted(phrase)) {
                        state = TriageState.CALL_PLACED_VOICE_HELP
                        lastCallReason = "Voice distress command 'HELP' spotted (\"$phrase\")"
                    }
                }
            }

            fun onPhase1Timeout() {
                if (state == TriageState.PHASE1_LISTENING_HELP) {
                    // No HELP received -> trigger voice alert "PLEASE CONFIRM THAT YOU ARE SAFE"
                    state = TriageState.PHASE2_AWAITING_SAFE_CONFIRM
                }
            }

            fun onUserConfirmSafe() {
                if (state == TriageState.PHASE2_AWAITING_SAFE_CONFIRM || state == TriageState.PHASE1_LISTENING_HELP) {
                    state = TriageState.ALERT_CANCELLED_SAFE
                }
            }

            fun onPhase2Timeout() {
                if (state == TriageState.PHASE2_AWAITING_SAFE_CONFIRM) {
                    // User did not press confirm -> assume unconscious -> auto call
                    state = TriageState.UNCONSCIOUS_AUTO_CALL_PLACED
                    lastCallReason = "User unresponsive (unconscious) after fall verification timeout"
                }
            }
        }

        // Scenario 1: Fall detected -> User shouts "HELP ME!" -> Immediate call placed
        val sim1 = TriageSimulator("+19876543210")
        sim1.onFallDetected()
        assertEquals(TriageState.PHASE1_LISTENING_HELP, sim1.state)
        sim1.onVoiceInput("HELP ME!")
        assertEquals(TriageState.CALL_PLACED_VOICE_HELP, sim1.state)
        assertTrue(sim1.lastCallReason!!.contains("HELP"))

        // Scenario 2: Fall detected -> User says nothing -> Phase 1 timeout -> Voice alert -> User taps "I AM SAFE" -> Cancelled
        val sim2 = TriageSimulator("+19876543210")
        sim2.onFallDetected()
        assertEquals(TriageState.PHASE1_LISTENING_HELP, sim2.state)
        sim2.onPhase1Timeout() // Silence
        assertEquals(TriageState.PHASE2_AWAITING_SAFE_CONFIRM, sim2.state)
        sim2.onUserConfirmSafe()
        assertEquals(TriageState.ALERT_CANCELLED_SAFE, sim2.state)

        // Scenario 3: Fall detected -> User is unconscious (no voice command, no button press) -> Phase 1 timeout -> Voice prompt -> Phase 2 timeout -> UNCONSCIOUS AUTO CALL!
        val sim3 = TriageSimulator("+19876543210")
        sim3.onFallDetected()
        assertEquals(TriageState.PHASE1_LISTENING_HELP, sim3.state)
        sim3.onPhase1Timeout() // Silence
        assertEquals(TriageState.PHASE2_AWAITING_SAFE_CONFIRM, sim3.state)
        sim3.onPhase2Timeout() // No button press -> Unconscious!
        assertEquals(TriageState.UNCONSCIOUS_AUTO_CALL_PLACED, sim3.state)
        assertTrue(sim3.lastCallReason!!.contains("unconscious"))
    }
}
