package nl.ncaj

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

internal class Contact(val email: String, var displayName: String) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null) return false
        if (this::class != other::class) return false

        other as Contact

        return email == other.email
    }

    override fun hashCode(): Int {
        return email.hashCode()
    }

    override fun toString(): String {
        return "nl.ncaj.Contact(email='$email', displayName='$displayName')"
    }
}

internal class ContactList(
    id: Int = 0,
    list: Set<Contact> = emptySet()
) {
    var id: Int = id
        private set

    private val _list = list.toMutableList()
    val list: List<Contact> get() = _list

    fun add(email: String, displayName: String) {
        _list.add(Contact(email, displayName))
        id++
    }

    fun remove(email: String) {
        find(email)?.let { _list.remove(it) }
        id++
    }

    fun find(email: String): Contact? = _list.find { it.email == email }

    fun contains(email: String) = find(email) != null
}

internal val testPrincipal = Principal(
    email = "test@hotmail.com",
    salt = "123456",
    password = "3cf424b562c400a59b4f9d2e518aaa92", // salt + 'k'
    displayName = "Alice",
    syncVersion = 1,
    forwardList = ContactList(list = setOf(Contact("test2@hotmail.com", "Bob"))),
    allowList = ContactList(list = setOf(Contact("test2@hotmail.com", "Bob"))),
    reverseList = ContactList(list = setOf(Contact("test2@hotmail.com", "Bob"))),
)

internal val test2Principal = Principal(
    email = "test2@hotmail.com",
    salt = "654321",
    password = "cfc3fcac144a3caf309d04445c40464c", // salt + 'f'
    displayName = "Bob",
    syncVersion = 1,
    forwardList = ContactList(list = setOf(Contact("test@hotmail.com", "Alice"))),
    allowList = ContactList(list = setOf(Contact("test@hotmail.com", "Alice"))),
    reverseList = ContactList(list = setOf(Contact("test@hotmail.com", "Alice"))),
)

private val _principals = mutableMapOf(
    testPrincipal.email to testPrincipal,
    test2Principal.email to test2Principal,
)

internal fun principals(email: String) = _principals[email]