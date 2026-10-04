package com.voicecontrol.core.screen

import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenParserTest {
    private val parser = ScreenParser()

    private fun signupScreen(emailValue: String? = null) = root(
        textView("Full name *", Bounds(40, 100, 600, 150)),
        editText(id = "com.shop:id/et_name", bounds = Bounds(40, 160, 1040, 280)),
        editText(id = "com.shop:id/et_email", hint = "Email address", text = emailValue,
            inputType = InputTypeBits.TYPE_CLASS_TEXT or InputTypeBits.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, bounds = Bounds(40, 320, 1040, 440)),
        editText(id = "com.shop:id/et_mobile", hint = "Mobile number", inputType = InputTypeBits.TYPE_CLASS_PHONE, bounds = Bounds(40, 480, 1040, 600)),
        editText(id = "com.shop:id/et_password", hint = "Password", text = "hunter22", password = true, bounds = Bounds(40, 640, 1040, 760)),
        editText(hint = "Enter OTP", text = "123456", inputType = InputTypeBits.TYPE_CLASS_NUMBER, bounds = Bounds(40, 800, 1040, 920)),
        button("Create account", id = "com.shop:id/btn_submit"),
        title = "Sign up",
    )

    @Test
    fun `lists fields and buttons with labels and types`() {
        val snapshot = parser.parse(signupScreen(), "com.shop", "com.shop.SignupActivity")

        assertEquals("Sign up", snapshot.title)
        assertEquals(5, snapshot.textFields.size)
        assertEquals(1, snapshot.buttons.size)

        val name = snapshot.textFields[0]
        assertEquals("Full name", name.label)
        assertEquals(FieldType.NAME, name.fieldType)
        assertEquals("vid:com.shop:id/et_name", name.id)

        assertEquals(FieldType.EMAIL, snapshot.textFields[1].fieldType)
        assertEquals(FieldType.PHONE, snapshot.textFields[2].fieldType)
        assertEquals("Create account", snapshot.buttons.single().label)
    }

    @Test
    fun `password and otp values are masked`() {
        val snapshot = parser.parse(signupScreen(), "com.shop")
        val password = snapshot.textFields[3]
        val otp = snapshot.textFields[4]
        assertTrue(password.isSensitive)
        assertNull(password.value)
        assertEquals(FieldType.PASSWORD, password.fieldType)
        assertTrue(otp.isSensitive)
        assertNull(otp.value)
        assertEquals(FieldType.OTP, otp.fieldType)
    }

    @Test
    fun `current value is read for normal fields but hint text is not a value`() {
        val empty = parser.parse(signupScreen(), "com.shop")
        assertNull(empty.textFields[1].value)
        val filled = parser.parse(signupScreen(emailValue = "a@b.com"), "com.shop")
        assertEquals("a@b.com", filled.textFields[1].value)
    }

    @Test
    fun `ids are stable across refreshes and label decorations`() {
        val first = parser.parse(signupScreen(), "com.shop").elements.map { it.id }
        val second = parser.parse(signupScreen(emailValue = "x@y.z"), "com.shop").elements.map { it.id }
        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size, "ids must be unique")
    }

    @Test
    fun `button text inside clickable container becomes its label and is not double counted`() {
        val card = FakeUiNode(
            className = "android.view.ViewGroup", isClickable = true, boundsInScreen = Bounds(0, 1000, 1080, 1200),
            children = listOf(textView("Continue to payment", Bounds(20, 1020, 800, 1080))),
        )
        val snapshot = parser.parse(root(card), "com.pay")
        assertEquals(1, snapshot.elements.size)
        assertEquals(ElementKind.BUTTON, snapshot.elements.single().kind)
        assertEquals("Continue to payment", snapshot.elements.single().label)
    }

    @Test
    fun `label to the left on the same row is used`() {
        val snapshot = parser.parse(
            root(
                textView("City", Bounds(20, 500, 200, 560)),
                editText(bounds = Bounds(220, 490, 1060, 570)),
            ),
            "com.form",
        )
        assertEquals("City", snapshot.textFields.single().label)
    }

    @Test
    fun `two fields never share the same nearby caption`() {
        val snapshot = parser.parse(
            root(
                textView("First name", Bounds(20, 100, 500, 150)),
                editText(bounds = Bounds(20, 160, 500, 260)),
                editText(bounds = Bounds(20, 280, 500, 380)),
            ),
            "com.form",
        )
        assertEquals("First name", snapshot.textFields[0].label)
        assertEquals("Text field", snapshot.textFields[1].label)
        assertTrue(snapshot.textFields[1].id.startsWith("path:"))
    }

    @Test
    fun `invisible nodes and disabled plain views are ignored`() {
        val snapshot = parser.parse(
            root(
                FakeUiNode(className = "android.widget.Button", text = "Hidden", isClickable = true, isVisibleToUser = false),
                editText(hint = "Search", bounds = Bounds(0, 0, 1000, 100)),
            ),
            "com.x",
        )
        assertEquals(listOf("Search"), snapshot.elements.map { it.label })
        assertEquals(FieldType.SEARCH, snapshot.elements.single().fieldType)
    }

    @Test
    fun `checkbox and switch are detected with checked state`() {
        val snapshot = parser.parse(
            root(
                FakeUiNode(className = "android.widget.CheckBox", text = "I agree to terms", isCheckable = true, isChecked = true, isClickable = true, boundsInScreen = Bounds(0, 100, 600, 160)),
                FakeUiNode(className = "android.widget.Switch", text = "Notifications", isCheckable = true, isClickable = true, boundsInScreen = Bounds(0, 200, 600, 260)),
            ),
            "com.x",
        )
        assertEquals(ElementKind.CHECKBOX, snapshot.elements[0].kind)
        assertEquals(true, snapshot.elements[0].isChecked)
        assertEquals(ElementKind.SWITCH, snapshot.elements[1].kind)
        assertEquals(false, snapshot.elements[1].isChecked)
    }

    @Test
    fun `generic hint is replaced by caption above`() {
        val snapshot = parser.parse(
            root(
                textView("Pincode", Bounds(20, 100, 500, 150)),
                editText(hint = "Type here", bounds = Bounds(20, 160, 500, 260)),
            ),
            "com.form",
        )
        assertEquals("Pincode", snapshot.textFields.single().label)
        assertEquals(FieldType.PINCODE, snapshot.textFields.single().fieldType)
    }

    @Test
    fun `signature ignores digits and value changes`() {
        val a = parser.parse(signupScreen(), "com.shop", "com.shop.SignupActivity").signature
        val b = parser.parse(signupScreen(emailValue = "q@w.e"), "com.shop", "com.shop.SignupActivity").signature
        assertEquals(a, b)
        assertTrue(a.startsWith("com.shop|SignupActivity|"))
        assertFalse(a.contains("hunter22"))
    }

    @Test
    fun `hindi labels survive normalization`() {
        assertEquals("मोबाइल नंबर", LabelText.normalize("मोबाइल नंबर:"))
        val snapshot = parser.parse(root(editText(hint = "मोबाइल नंबर", bounds = Bounds(0, 0, 900, 100))), "com.hi")
        assertEquals(FieldType.PHONE, snapshot.textFields.single().fieldType)
    }
}

class ParseWithNodesTest {
    @Test
    fun `every element id maps back to its live node`() {
        val submit = button("Pay now", id = "com.pay:id/pay")
        val field = editText(hint = "Amount", bounds = Bounds(0, 0, 900, 100))
        val result = ScreenParser().parseWithNodes(root(field, submit), "com.pay")
        assertEquals(2, result.nodesById.size)
        assertTrue(result.nodesById["vid:com.pay:id/pay"] === submit)
        val fieldId = result.snapshot.textFields.single().id
        assertTrue(result.nodesById[fieldId] === field)
    }

    @Test
    fun `a search box is called Search, never by what is typed in it`() {
        val typed = editText(id = "com.yt:id/search_edit_text", text = "arijit songs").copy(contentDescription = "arijit songs")
        val view = FakeUiNode(
            className = "android.widget.SearchView\$SearchAutoComplete", text = "kesariya", isEditable = true,
            boundsInScreen = Bounds(0, 300, 1000, 420),
        )
        val snapshot = ScreenParser().parse(root(typed, view), "com.yt")
        val first = snapshot.textFields[0]
        val second = snapshot.textFields[1]
        assertEquals("Search", first.label)
        assertEquals("arijit songs", first.value)
        assertEquals(FieldType.SEARCH, first.fieldType)
        assertEquals("Search", second.label)
        assertEquals(FieldType.SEARCH, second.fieldType)
    }
}
