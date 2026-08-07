package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "external_list_domains",
    foreignKeys = [
        ForeignKey(
            entity = ExternalList::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["listId"]),
        Index(value = ["domain"])
    ]
)
data class ExternalListDomain(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val listId: Int,
    val domain: String
)
