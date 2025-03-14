package nl.ncaj

import java.util.UUID

internal class Principal(
    val email: String,
    val salt: String,
    val password: String,
    var displayName: String,
    var status: String = "FLN",
    var syncVersion: Int = 0,
    val forwardList: ContactList = ContactList(), // Users on your contact list.
    val reverseList: ContactList = ContactList(), // Users who have you on their contact list.
    val allowList: ContactList = ContactList(), // Users who are allowed to see your status.
    val blockList: ContactList = ContactList(), //  Users who are not allowed to see your status.
    val privacy: String = "AL",
    val privacyAdd: String = "N",
)

internal class Contact(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    var displayName: String
)

internal class ContactList(
    val id: String = UUID.randomUUID().toString(),
    version: Int = 0,
    var list: MutableSet<Contact> = mutableSetOf<Contact>()
) {
    var version: Int = version
        private set

    fun add(email: String, displayName: String) {
        if (!contains(email)) {
            val contact = Contact(email = email, displayName = displayName)
            list.add(contact)
            this@ContactList.version++
        }
    }

    fun remove(email: String) {
        val existing = list.find { it.email == email }
        if (existing != null) {
            list.remove(existing)
            this@ContactList.version++
        }
    }

    fun contains(email: String) = list.find { it.email == email } != null
}