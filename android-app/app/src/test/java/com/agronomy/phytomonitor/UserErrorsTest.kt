package com.agronomy.phytomonitor

import android.database.sqlite.SQLiteFullException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserErrorsTest {
    @Test fun fullStorageIsExplainedWhereverItIsWrapped() {
        val write = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(UserErrors.NO_SPACE, UserErrors.describe(write, "x"))
        assertEquals(UserErrors.NO_SPACE, UserErrors.describe(IllegalStateException("draft", write), "x"))
        assertEquals(UserErrors.NO_SPACE, UserErrors.describe(SQLiteFullException("database or disk is full"), "x"))
    }

    @Test fun otherErrorsKeepTheirOwnMessage() {
        assertEquals("Повреждён файл модели", UserErrors.describe(IllegalArgumentException("Повреждён файл модели"), "x"))
        assertEquals("x", UserErrors.describe(IOException(), "x"))
    }
}
