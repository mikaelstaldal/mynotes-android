package nu.staldal.mynotes.data.local

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()
    private val tagListType = object : TypeToken<List<TagEntity>>() {}.type

    @TypeConverter
    fun fromTagList(tags: List<TagEntity>): String = gson.toJson(tags)

    @TypeConverter
    fun toTagList(json: String): List<TagEntity> = gson.fromJson(json, tagListType) ?: emptyList()
}
