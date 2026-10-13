package studio.forest.keyboard

import android.content.Context

internal object KeyboardPreferences {
    const val FILE = "forest_learning"
    const val AUTO_LEARNING = "setting_auto_learning"
    const val HAPTIC_FEEDBACK = "setting_haptic_feedback"
    const val KEY_SOUND = "setting_key_sound"
    const val LEARNING_REVISION = "learning_revision"
    const val CUSTOM_REVISION = "custom_dictionary_revision"
    const val CUSTOM_PREFIX = "custom|"

    fun customPhrases(context: Context): Map<String,List<String>> {
        val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key,value) ->
            if(!key.startsWith(CUSTOM_PREFIX) || value !is String) return@mapNotNull null
            val py=key.removePrefix(CUSTOM_PREFIX)
            val words=value.split('\n').filter { it.isNotBlank() }
            if(py.isBlank() || words.isEmpty()) null else py to words
        }.toMap()
    }

    fun addCustomPhrase(context: Context, pinyin: String, phrase: String) {
        val py=pinyin.lowercase()
        val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        val key=CUSTOM_PREFIX+py
        val words=prefs.getString(key,"").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableList()
        if(phrase.trim() !in words) words.add(phrase.trim())
        prefs.edit().putString(key,words.joinToString("\n"))
            .putInt(CUSTOM_REVISION,prefs.getInt(CUSTOM_REVISION,0)+1).apply()
    }

    fun removeCustomPhrases(context: Context, pinyin: String) {
        val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        prefs.edit().remove(CUSTOM_PREFIX+pinyin)
            .putInt(CUSTOM_REVISION,prefs.getInt(CUSTOM_REVISION,0)+1).apply()
    }

    fun clearLearningData(context: Context): Int {
        val prefs=context.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        val frequencyKeys=prefs.all.keys.filter { it.startsWith("freq|") }
        val editor=prefs.edit()
        frequencyKeys.forEach(editor::remove)
        editor.putInt(LEARNING_REVISION,prefs.getInt(LEARNING_REVISION,0)+1).apply()
        return frequencyKeys.size
    }
}
