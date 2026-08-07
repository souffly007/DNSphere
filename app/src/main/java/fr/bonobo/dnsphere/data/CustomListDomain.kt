package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custom_list_domains",
    foreignKeys = [
        ForeignKey(
            entity = CustomList::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["listId"])]
)
data class CustomListDomain(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val listId: Long,
    val domain: String
)
