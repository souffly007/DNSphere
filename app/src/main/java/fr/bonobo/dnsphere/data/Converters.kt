package fr.bonobo.dnsphere.data

import androidx.room.TypeConverter

class Converters {
    
    @TypeConverter
    fun fromListCategory(category: ListCategory): String = category.name
    
    @TypeConverter
    fun toListCategory(value: String): ListCategory = ListCategory.valueOf(value)
    
    @TypeConverter
    fun fromListFormat(format: ListFormat): String = format.name
    
    @TypeConverter
    fun toListFormat(value: String): ListFormat = ListFormat.valueOf(value)
}
