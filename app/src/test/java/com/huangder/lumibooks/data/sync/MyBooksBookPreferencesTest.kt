package com.huangder.lumibooks.data.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.huangder.lumibooks.data.local.DataStoreManager
import com.huangder.lumibooks.data.local.copyBookScopedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MyBooksBookPreferencesTest {
    @Test fun associationCopiesBookScopedReviewAndRatingWithoutDeletingTheOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = DataStoreManager(context)
        val source = UUID.randomUUID().toString(); val target = UUID.randomUUID().toString()
        store.saveBookReview(source, "Kept local review", 4f)
        copyBookScopedPreferences(context, source, target)
        assertEquals("Kept local review", store.bookReview(target).first())
        assertEquals(4f, store.bookReviewRating(target).first(), 0f)
        assertEquals("Kept local review", store.bookReview(source).first())
    }
    @Test fun copyingCannotOverwriteAnExistingTargetsBookPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = DataStoreManager(context)
        val source = UUID.randomUUID().toString(); val target = UUID.randomUUID().toString()
        store.saveBookReview(source, "Source review", 2f)
        store.saveBookReview(target, "Existing target", 5f)
        copyBookScopedPreferences(context, source, target)
        assertEquals("Existing target", store.bookReview(target).first())
        assertEquals(5f, store.bookReviewRating(target).first(), 0f)
    }
}
