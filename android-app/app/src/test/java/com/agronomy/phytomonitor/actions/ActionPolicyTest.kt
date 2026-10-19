package com.agronomy.phytomonitor.actions

import com.agronomy.phytomonitor.cropOptions
import com.agronomy.phytomonitor.voice.VoiceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPolicyTest {
    private val withResult = AppState(
        hasPhoto = true,
        hasResult = true,
        selectedModel = "yolo26n",
        availableModels = listOf("yolo26n", "yolo26s")
    )

    private fun voice(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.VOICE)

    private fun button(action: VoiceAction, value: String = "") = ActionRequest(action, value, ActionSource.BUTTON)

    private fun operations(verdict: Verdict): List<Operation> = (verdict as Verdict.Run).operations

    @Test
    fun missingPhotoAndResultAskForTheMissingStep() {
        val empty = AppState()
        assertEquals(Verdict.Clarify(ActionPolicy.NEED_PHOTO), ActionPolicy.resolve(voice(VoiceAction.ANALYZE), empty))
        assertEquals(Verdict.Clarify(ActionPolicy.NEED_PHOTO), ActionPolicy.resolve(voice(VoiceAction.COMPARE), empty))
        assertEquals(Verdict.Clarify(ActionPolicy.NEED_RESULT), ActionPolicy.resolve(voice(VoiceAction.SPEAK_RESULT), empty))
        assertEquals(Verdict.Clarify(ActionPolicy.NOTHING_TO_SAVE), ActionPolicy.resolve(button(VoiceAction.SAVE_INSPECTION), empty))
        assertEquals(Verdict.Clarify(ActionPolicy.NEED_RESULT), ActionPolicy.resolve(voice(VoiceAction.ASSESS, "correct"), empty))
        val photoOnly = AppState(hasPhoto = true)
        assertEquals(listOf(Operation.Analyze), operations(ActionPolicy.resolve(voice(VoiceAction.ANALYZE), photoOnly)))
    }

    @Test
    fun busyAppWaitsButControlsStillWork() {
        val busy = withResult.copy(busy = true)
        assertEquals(Verdict.Clarify(ActionPolicy.WAIT), ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION), busy))
        assertEquals(Verdict.Clarify(ActionPolicy.WAIT), ActionPolicy.resolve(voice(VoiceAction.OPEN_CAMERA), busy))
        assertEquals(listOf(Operation.StopSpeech), operations(ActionPolicy.resolve(voice(VoiceAction.SILENCE), busy)))
        assertEquals(
            listOf(Operation.SetContinuousListening(false)),
            operations(ActionPolicy.resolve(voice(VoiceAction.LISTEN_OFF), busy))
        )
        assertEquals(listOf(Operation.OpenJournal), operations(ActionPolicy.resolve(button(VoiceAction.JOURNAL), busy)))
        assertEquals(listOf(Operation.ShowBoxes(false)), operations(ActionPolicy.resolve(button(VoiceAction.HIDE_BOXES), busy)))
    }

    @Test
    fun savedInspectionIsNotSavedAgainUntilSomethingChanges() {
        val saved = withResult.copy(inspectionSaved = true, review = "incorrect")
        assertEquals(Verdict.Skip(ActionPolicy.ALREADY_SAVED), ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION), saved))
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ASSESS, "incorrect"), saved) is Verdict.Skip)
        assertEquals(
            listOf(Operation.Assess("correct")),
            operations(ActionPolicy.resolve(voice(VoiceAction.ASSESS, "correct"), saved))
        )
        assertEquals(
            listOf(Operation.SaveInspection),
            operations(ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION), withResult))
        )
    }

    @Test
    fun dictationKeepsSpeechAsTextAndOnlyDialogButtonsAct() {
        val dictating = withResult.copy(screen = Screen.NOTE)
        listOf(VoiceAction.SAVE_INSPECTION, VoiceAction.OPEN_CAMERA, VoiceAction.CANCEL, VoiceAction.EXIT, VoiceAction.CONFIRM).forEach {
            assertEquals(it.name, Verdict.Dictate, ActionPolicy.resolve(voice(it), dictating))
            assertEquals(it.name, Verdict.Dictate, ActionPolicy.resolve(ActionRequest(it, source = ActionSource.ASSISTANT), dictating))
        }
        assertEquals(listOf(Operation.CloseNote), operations(ActionPolicy.resolve(button(VoiceAction.CANCEL), dictating)))
        assertEquals(
            listOf(Operation.CloseNote, Operation.SaveInspection),
            operations(ActionPolicy.resolve(button(VoiceAction.CONFIRM), dictating))
        )
        assertTrue(ActionPolicy.resolve(button(VoiceAction.OPEN_CAMERA), dictating) is Verdict.Clarify)
    }

    @Test
    fun noteReviewConfirmationClosesTheNoteAndUsesSaveChecks() {
        val review = withResult.copy(screen = Screen.NOTE_REVIEW)
        assertEquals(
            listOf(Operation.CloseNote, Operation.SaveInspection),
            operations(ActionPolicy.resolve(voice(VoiceAction.CONFIRM), review))
        )
        val alreadySaved = ActionPolicy.resolve(voice(VoiceAction.CONFIRM), review.copy(inspectionSaved = true)) as Verdict.Run
        assertEquals(listOf(Operation.CloseNote), alreadySaved.operations)
        assertEquals(ActionPolicy.ALREADY_SAVED, alreadySaved.notice)
        val noResult = ActionPolicy.resolve(voice(VoiceAction.CONFIRM), AppState(screen = Screen.NOTE_REVIEW)) as Verdict.Run
        assertEquals(listOf(Operation.CloseNote), noResult.operations)
        assertTrue(noResult.noticeIsError)
        assertEquals(Verdict.Clarify(ActionPolicy.WAIT), ActionPolicy.resolve(voice(VoiceAction.CONFIRM), review.copy(busy = true)))
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION), review) is Verdict.Clarify)
        assertEquals(listOf(Operation.OpenNote(dictate = true)), operations(ActionPolicy.resolve(voice(VoiceAction.NOTE), review)))
    }

    @Test
    fun backClosesOneLayerAtATime() {
        val galleryWithHelp = AppState(screen = Screen.GALLERY, helpOpen = true)
        assertEquals(listOf(Operation.ShowHelp(false)), operations(ActionPolicy.resolve(voice(VoiceAction.BACK), galleryWithHelp)))
        assertEquals(
            listOf(Operation.ClosePhotoFlow),
            operations(ActionPolicy.resolve(voice(VoiceAction.BACK), galleryWithHelp.copy(helpOpen = false)))
        )
        assertEquals(listOf(Operation.CloseJournal), operations(ActionPolicy.resolve(voice(VoiceAction.CANCEL), AppState(screen = Screen.JOURNAL))))
        assertEquals(listOf(Operation.ClosePhotoFlow), operations(ActionPolicy.resolve(voice(VoiceAction.CANCEL), AppState(screen = Screen.CAMERA))))
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.CANCEL), AppState()) is Verdict.Skip)
    }

    @Test
    fun confirmationNeedsAnObjectOnTheScreen() {
        assertEquals(Verdict.Clarify(ActionPolicy.WHICH_ACTION), ActionPolicy.resolve(voice(VoiceAction.CONFIRM), withResult))
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.CONFIRM), AppState(screen = Screen.GALLERY, galleryCount = 5)) is Verdict.Clarify)
        val selected = AppState(screen = Screen.GALLERY, galleryCount = 5, selectedPhoto = 2)
        assertEquals(listOf(Operation.ConfirmGallery), operations(ActionPolicy.resolve(voice(VoiceAction.CONFIRM), selected)))
        assertEquals(listOf(Operation.TakeShot), operations(ActionPolicy.resolve(voice(VoiceAction.CONFIRM), AppState(screen = Screen.CAMERA))))
        val capturing = AppState(screen = Screen.CAMERA, cameraCapturing = true)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.CONFIRM), capturing) is Verdict.Skip)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.CAMERA_NOW), capturing) is Verdict.Skip)
    }

    @Test
    fun galleryNumbersStayInsideTheGallery() {
        val gallery = AppState(screen = Screen.GALLERY, galleryCount = 12, selectedPhoto = 4)
        assertEquals(listOf(Operation.SelectPhoto(7)), operations(ActionPolicy.resolve(voice(VoiceAction.PHOTO_NUMBER, "7"), gallery)))
        assertEquals(listOf(Operation.SelectPhoto(5)), operations(ActionPolicy.resolve(voice(VoiceAction.NEXT_PHOTO), gallery)))
        assertEquals(listOf(Operation.SelectPhoto(3)), operations(ActionPolicy.resolve(voice(VoiceAction.PREVIOUS_PHOTO), gallery)))
        assertEquals(listOf(Operation.SelectPhoto(12)), operations(ActionPolicy.resolve(voice(VoiceAction.LAST_PHOTO), gallery)))
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PHOTO_NUMBER, "13"), gallery) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PHOTO_NUMBER, "0"), gallery) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.NEXT_PHOTO), gallery.copy(selectedPhoto = 12)) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.PHOTO_NUMBER, "5"), withResult) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.FIRST_PHOTO), gallery.copy(galleryCount = 0)) is Verdict.Clarify)
    }

    @Test
    fun inspectionActionsWorkOnlyNextToThePhoto() {
        listOf(Screen.CAMERA, Screen.GALLERY, Screen.JOURNAL).forEach { screen ->
            val state = withResult.copy(screen = screen)
            listOf(VoiceAction.SAVE_INSPECTION, VoiceAction.SPEAK_RESULT, VoiceAction.ANALYZE, VoiceAction.NOTE).forEach {
                assertTrue("$screen $it", ActionPolicy.resolve(voice(it), state) is Verdict.Clarify)
            }
        }
    }

    @Test
    fun argumentsAreCheckedBeforeAnything() {
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.MODEL, "dfine_n"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.CROP, "Bean"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.SCROLL, "left"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.ASSESS, "maybe"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.SAVE_INSPECTION, "all"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD, "сорок"), withResult) is Verdict.Reject)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD, "120"), withResult) is Verdict.Clarify)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD, "-5"), withResult) is Verdict.Clarify)
        assertEquals(cropOptions.map { it.first }.toSet(), ActionPolicy.crops)
    }

    @Test
    fun settingsReanalyseOnlyOnTheMainScreen() {
        assertEquals(
            listOf(Operation.SelectModel("yolo26s", analyze = true)),
            operations(ActionPolicy.resolve(voice(VoiceAction.MODEL, "yolo26s"), withResult))
        )
        assertEquals(
            listOf(Operation.SelectCrop("Tomato", analyze = false)),
            operations(ActionPolicy.resolve(voice(VoiceAction.CROP, "Tomato"), withResult.copy(screen = Screen.GALLERY)))
        )
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.MODEL, "yolo26n"), withResult) is Verdict.Skip)
        assertEquals(
            listOf(Operation.SetThreshold(0.4f, analyze = true)),
            operations(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD, "40"), withResult))
        )
        assertEquals(
            listOf(Operation.SetThreshold(0.3f, analyze = false)),
            operations(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD_UP), AppState()))
        )
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD_UP), AppState(threshold = 0.8f)) is Verdict.Skip)
        assertTrue(ActionPolicy.resolve(voice(VoiceAction.THRESHOLD_DOWN), AppState(threshold = 0.1f)) is Verdict.Skip)
    }

    @Test
    fun voiceOpensDialogsWithListeningButtonsDoNot() {
        assertEquals(
            listOf(Operation.OpenCamera(immediate = false, withVoice = true)),
            operations(ActionPolicy.resolve(voice(VoiceAction.CAMERA), AppState()))
        )
        assertEquals(
            listOf(Operation.OpenCamera(immediate = false, withVoice = false)),
            operations(ActionPolicy.resolve(button(VoiceAction.OPEN_CAMERA), AppState()))
        )
        assertEquals(listOf(Operation.OpenGallery(withVoice = false)), operations(ActionPolicy.resolve(button(VoiceAction.GALLERY), AppState())))
        assertEquals(listOf(Operation.OpenNote(dictate = true)), operations(ActionPolicy.resolve(voice(VoiceAction.NOTE), AppState())))
        assertEquals(listOf(Operation.OpenNote(dictate = false)), operations(ActionPolicy.resolve(button(VoiceAction.NOTE), AppState())))
        assertEquals(
            listOf(Operation.SetContinuousListening(false), Operation.Exit),
            operations(ActionPolicy.resolve(voice(VoiceAction.EXIT), AppState()))
        )
    }
}
