package com.voicecontrol.core.nlp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneTaskTest {
    private fun p(s: String) = PhoneTask.parse(s)

    @Test
    fun alarms() {
        assertEquals(PhoneTask.Alarm(6, 0), p("subah 6 baje ka alarm laga do"))
        assertEquals(PhoneTask.Alarm(6, 30), p("set an alarm for 6:30 am"))
        assertEquals(PhoneTask.Alarm(19, 0), p("shaam 7 baje alarm lagao"))
        assertEquals(PhoneTask.Alarm(5, 30), p("साढ़े 5 बजे का अलार्म लगा दो"))
        assertEquals(PhoneTask.Alarm(7, 45), p("paune 8 baje utha dena"))
        assertNull(p("alarm kholo"))
    }

    @Test
    fun timers() {
        assertEquals(PhoneTask.Timer(300), p("set a timer for 5 minutes"))
        assertEquals(PhoneTask.Timer(600), p("10 minute ka timer lagao"))
        assertEquals(PhoneTask.Timer(1800), p("aadha ghanta ka timer"))
        assertEquals(PhoneTask.Timer(90), p("timer 1 minute 30 second"))
    }

    @Test
    fun searches() {
        assertEquals(PhoneTask.Search(SearchPlace.YOUTUBE, "arijit singh songs"), p("YouTube pe Arijit Singh songs chalao"))
        assertEquals(PhoneTask.Search(SearchPlace.YOUTUBE, "kesariya"), p("play kesariya on youtube"))
        assertEquals(PhoneTask.Search(SearchPlace.WEB, "weather in pune"), p("google pe weather in pune search karo"))
        assertEquals(PhoneTask.Search(SearchPlace.WEB, "पनीर रेसिपी"), p("पनीर रेसिपी सर्च करो"))
        assertEquals(PhoneTask.Search(SearchPlace.MAPS, "railway station"), p("railway station ka rasta"))
        assertNull(p("YouTube kholo"))
        assertNull(p("youtube chalao"))
    }

    @Test
    fun callsAndMessages() {
        assertEquals(PhoneTask.Call("rahul"), p("Rahul ko call karo"))
        assertEquals(PhoneTask.Call("mummy"), p("मम्मी को फोन लगाओ".replace("मम्मी", "mummy")))
        assertEquals(PhoneTask.Call("9876543210"), p("call 9876543210"))
        assertEquals(PhoneTask.Message("mummy", "main aa raha hoon"), p("Mummy ko WhatsApp pe message bhejo ki main aa raha hoon"))
        assertEquals(PhoneTask.Message("priya", "running late"), p("send a message to Priya saying running late"))
        assertNull(p("call history dikhao".replace("call history dikhao", "history dikhao")))
    }

    @Test
    fun timeAndDate() {
        assertEquals(PhoneTask.TimeNow, p("abhi time kya hua hai"))
        assertEquals(PhoneTask.TimeNow, p("What time is it?"))
        assertEquals(PhoneTask.DateToday, p("आज की तारीख क्या है"))
        assertNull(p("Next"))
        assertNull(p("Login"))
    }

    @Test
    fun goals() {
        listOf(
            "mujhe bijli ka bill bharna hai", "I want to book a train ticket", "recharge kaise kare",
            "PhonePe kholo aur 100 ka recharge karo", "मुझे बस का टिकट चाहिए", "help me send money to Ravi",
        ).forEach { kotlin.test.assertTrue(GoalRequest.isGoal(it), it) }
        listOf("Login", "WhatsApp kholo", "next", "pata nahi", "Pay bill").forEach { kotlin.test.assertFalse(GoalRequest.isGoal(it), it) }
    }

    @Test
    fun phoneControls() {
        assertEquals(PhoneTask.Torch(true), p("torch jalao"))
        assertEquals(PhoneTask.Torch(false), p("torch band karo"))
        assertEquals(PhoneTask.Torch(true), p("टॉर्च जलाओ"))
        assertEquals(PhoneTask.Volume(VolumeChange.UP), p("awaaz badhao"))
        assertEquals(PhoneTask.Volume(VolumeChange.DOWN), p("volume kam karo"))
        assertEquals(PhoneTask.Volume(VolumeChange.MAX), p("volume full karo"))
        assertEquals(PhoneTask.Volume(VolumeChange.MUTE), p("phone silent karo"))
        assertEquals(PhoneTask.Battery, p("battery kitni hai"))
        assertEquals(PhoneTask.OpenSettings(SettingsPage.WIFI), p("wifi on karo"))
        assertEquals(PhoneTask.OpenSettings(SettingsPage.BLUETOOTH), p("bluetooth kholo"))
        assertEquals(PhoneTask.OpenSettings(SettingsPage.MAIN), p("settings kholo"))
        assertNull(p("YouTube kholo"))
        assertNull(p("WhatsApp start karo"))
    }

    @Test
    fun remindersNotificationsAndHelp() {
        assertEquals(PhoneTask.Reminder(21, 0, "dawai"), p("raat 9 baje dawai ki yaad dilana"))
        assertEquals(PhoneTask.Reminder(8, 30, "call mom"), p("remind me at 8:30 am to call mom"))
        assertEquals(PhoneTask.Timer(600), p("10 minute baad yaad dilana"))
        assertEquals(PhoneTask.ReadNotifications, p("kya naya message aaya hai"))
        assertEquals(PhoneTask.ReadNotifications, p("read my notifications"))
        assertEquals(PhoneTask.Capabilities, p("tum kya kya kar sakte ho"))
        assertEquals(PhoneTask.Capabilities, p("What can you do?"))
    }

    @Test
    fun morePhoneControls() {
        assertEquals(PhoneTask.System(SystemAction.HOME), p("home screen pe jao"))
        assertEquals(PhoneTask.System(SystemAction.RECENTS), p("recent apps dikhao"))
        assertEquals(PhoneTask.System(SystemAction.NOTIFICATIONS), p("notification panel kholo"))
        assertEquals(PhoneTask.System(SystemAction.QUICK_SETTINGS), p("quick settings kholo"))
        assertEquals(PhoneTask.System(SystemAction.LOCK), p("phone lock karo"))
        assertEquals(PhoneTask.System(SystemAction.SCREENSHOT), p("screenshot lo"))
        assertEquals(PhoneTask.System(SystemAction.POWER_MENU), p("phone band karo"))
        assertEquals(PhoneTask.Volume(VolumeChange.MUTE), p("phone silent karo"))
        assertEquals(PhoneTask.Camera(video = false, selfie = false), p("camera kholo"))
        assertEquals(PhoneTask.Camera(video = false, selfie = true), p("selfie lo"))
        assertEquals(PhoneTask.Camera(video = true, selfie = false), p("video banao"))
        assertEquals(PhoneTask.Camera(video = false, selfie = false), p("photo khincho"))
        assertEquals(PhoneTask.Brightness(VolumeChange.UP), p("brightness badhao"))
        assertEquals(PhoneTask.Brightness(VolumeChange.DOWN), p("roshni kam karo"))
        assertEquals(PhoneTask.Media(MediaKey.PAUSE), p("gaana roko"))
        assertEquals(PhoneTask.Media(MediaKey.NEXT), p("agla gaana"))
        assertEquals(PhoneTask.Media(MediaKey.PREVIOUS), p("pichhla gaana lagao"))
        assertEquals(PhoneTask.Media(MediaKey.PLAY), p("gaana chalao"))
        assertEquals(PhoneTask.Search(SearchPlace.YOUTUBE, "arijit ke gaane"), p("Arijit ke gaane chalao"))
        assertEquals(PhoneTask.OpenSettings(SettingsPage.AIRPLANE), p("flight mode on karo"))
        assertEquals(PhoneTask.OpenSettings(SettingsPage.DO_NOT_DISTURB), p("do not disturb on karo"))
    }

    @Test
    fun arithmetic() {
        assertEquals(PhoneTask.Calculate(100.0), p("25 guna 4 kitna hota hai"))
        assertEquals(PhoneTask.Calculate(18.0), p("100 ka 18 percent"))
        assertEquals(PhoneTask.Calculate(70.0), p("100 mein se 30 ghatao"))
        assertEquals(PhoneTask.Calculate(10.0), p("50 divided by 5"))
        assertEquals(PhoneTask.Calculate(15.0), p("7 plus 8"))
        assertNull(p("100 ka recharge karo"))
        assertEquals(PhoneTask.Alarm(8, 30), p("8:30 ka alarm"))
    }

    @Test
    fun emergencyAndContacts() {
        assertEquals(PhoneTask.Emergency, p("bachao"))
        assertEquals(PhoneTask.Emergency, p("emergency hai"))
        assertEquals(PhoneTask.Emergency, p("मदद करो"))
        assertEquals(PhoneTask.SetEmergencyContact("rahul"), p("mera emergency contact Rahul hai"))
        assertEquals(PhoneTask.SetEmergencyContact("kishor"), p("set Kishor as my emergency contact"))
        assertEquals(PhoneTask.ContactNumber("rahul"), p("Rahul ka number kya hai"))
        assertEquals(PhoneTask.ContactNumber("mummy"), p("what is the number of mummy"))
        assertNull(p("help"))
    }

    @Test
    fun notesAlarmsAndQuickSearches() {
        assertEquals(PhoneTask.NoteAdd("doodh lana hai"), p("note karo ki doodh lana hai"))
        assertEquals(PhoneTask.NoteAdd("call the plumber"), p("make a note call the plumber"))
        assertEquals(PhoneTask.NotesRead, p("mere notes padho"))
        assertEquals(PhoneTask.NotesClear, p("notes mita do"))
        assertEquals(PhoneTask.ShowAlarms, p("mere alarm dikhao"))
        assertEquals(PhoneTask.Alarm(6, 0), p("6 baje ka alarm laga do"))
        assertEquals(PhoneTask.Search(SearchPlace.WEB, "weather today"), p("aaj mausam kaisa hai"))
        assertEquals(PhoneTask.Search(SearchPlace.WEB, "latest news"), p("aaj ki khabar sunao"))
        assertEquals(PhoneTask.Search(SearchPlace.WEB, "cricket score"), p("cricket match ka score kya hai"))
    }

    @Test
    fun pronounsNameThePersonTalkedAbout() {
        assertEquals(PhoneTask.Call("usko"), p("usko call karo"))
        assertEquals(PhoneTask.Call("him"), p("call him"))
        assertEquals(PhoneTask.Message("unko", null), p("unko message bhejo"))
        assertEquals(PhoneTask.Call("उसको"), p("उसको फोन लगाओ"))
        assertEquals(PhoneTask.ContactNumber("uska"), p("uska number kya hai"))
    }
}
