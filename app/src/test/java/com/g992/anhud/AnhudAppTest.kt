package com.g992.anhud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnhudAppTest {
    @Test
    fun recognizesAndroidIsolatedServiceProcessName() {
        assertTrue(
            isCustomScriptSandboxProcess(
                "com.g992.anhud:custom_script_sandbox:com.g992.anhud.CustomScriptSandboxService"
            )
        )
        assertTrue(isCustomScriptSandboxProcess("com.g992.anhud:custom_script_sandbox"))
        assertFalse(isCustomScriptSandboxProcess("com.g992.anhud"))
    }
}
