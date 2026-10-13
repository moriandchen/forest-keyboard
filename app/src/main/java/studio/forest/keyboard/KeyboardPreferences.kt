package studio.forest.keyboard

import android.content.Context

internal object KeyboardPreferences {
    const val FILE = "forest_learning"
    const val AUTO_LEARNING = "setting_auto_learning"
    const val HAPTIC_FEEDBACK = "setting_haptic_feedback"
    const val KEY_SOUND = "setting_key_sound"
    const val LEARNING_REVISION = "learning_revision"

    fun clearLearningData(context: Context): Int {
        val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        val frequencyKeys=prefs.all.keys.filter { it.startsWith("freq|") }
        val editor=prefs.edit()
        frequencyKeys.forEach(editor::remove)
        editor.putInt(LEARNING_REVISION,prefs.getInt(LEARNING_REVISION,0)+1).apply()
        return frequencyKeys.size
    }
}
