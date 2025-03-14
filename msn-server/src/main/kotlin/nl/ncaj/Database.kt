package nl.ncaj

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

internal class Database(fileLocation: String) {
    private val jdbcUrl = "jdbc:sqlite:$fileLocation"

    fun getPrincipal(email: String) = execute {
        val query = "SELECT * FROM principal WHERE email = ?"
        val statement = prepareStatement(query).apply { setString(1, email) }

        val resultSet = statement.executeQuery()

        val principal = if (resultSet.next()) {
            Principal(
                email = resultSet.getString("email"),
                salt = resultSet.getString("salt"),
                password = resultSet.getString("password"),
                displayName = resultSet.getString("display_name"),
                status = resultSet.getString("status"),
                syncVersion = resultSet.getInt("version"),
                privacy = resultSet.getString("privacy"),
                privacyAdd = resultSet.getString("privacy_add"),
                forwardList = resultSet.getString("forward_list")
                    ?.let { ContactList(it, resultSet.getInt("forward_list_version")) }
                    ?: ContactList(),
                allowList = resultSet.getString("allow_list")
                    ?.let { ContactList(it, resultSet.getInt("allow_list_version")) }
                    ?: ContactList(),
                blockList = resultSet.getString("block_list")
                    ?.let { ContactList(it, resultSet.getInt("block_list_version")) }
                    ?: ContactList(),
                reverseList = resultSet.getString("reverse_list")
                    ?.let { ContactList(it, resultSet.getInt("reverse_list_version")) }
                    ?: ContactList(),
            )
        } else null

        resultSet.close()
        statement.close()

        if (principal != null) {
            principal.forwardList.list = getList(principal.email, "forward")
            principal.allowList.list = getList(principal.email, "allow")
            principal.blockList.list = getList(principal.email, "block")
            principal.reverseList.list = getList(principal.email, "reverse")
        }

        return@execute principal
    }

    private fun Connection.getList(email: String, list: String): MutableSet<Contact> {
        val query = """
            SELECT contact_list.email, contact_list.display_name, contact_list.id
                FROM contact_list
                JOIN principal ON contact_list.list_id = principal.${list}_list
                WHERE principal.email = ?
        """.trimIndent()
        val statement = prepareStatement(query).apply { setString(1, email) }
        val resultSet = statement.executeQuery()

        val contacts = mutableSetOf<Contact>()
        while (resultSet.next()) {
            val contact = Contact(
                resultSet.getString("id"),
                resultSet.getString("email"),
                resultSet.getString("display_name")
            )
            contacts.add(contact)
        }

        resultSet.close()
        statement.close()
        return contacts
    }

    fun updatePrincipal(principal: Principal) = execute {
        updateList(principal.forwardList)
        updateList(principal.allowList)
        updateList(principal.blockList)
        updateList(principal.reverseList)

        val query = """
            INSERT OR REPLACE INTO principal (email, salt, password, display_name, status, version, privacy, 
                privacy_add, forward_list, forward_list_version, allow_list, allow_list_version, block_list, 
                block_list_version, reverse_list, reverse_list_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        val statement = prepareStatement(query).apply {
            setString(1, principal.email)
            setString(2, principal.salt)
            setString(3, principal.password)
            setString(4, principal.displayName)
            setString(5, principal.status)
            setInt(6, principal.syncVersion)
            setString(7, principal.privacy)
            setString(8, principal.privacyAdd)
            setString(9, principal.forwardList.id)
            setInt(10, principal.forwardList.version)
            setString(11, principal.allowList.id)
            setInt(12, principal.allowList.version)
            setString(13, principal.blockList.id)
            setInt(14, principal.blockList.version)
            setString(15, principal.reverseList.id)
            setInt(16, principal.reverseList.version)
        }

        statement.executeUpdate()
        statement.close()
    }

    private fun Connection.updateList(list: ContactList) {
        prepareStatement("DELETE FROM contact_list WHERE list_id = ?")
            .apply { setString(1, list.id) }
            .use { it.executeUpdate() }

        list.list.forEach { contact ->
            val query = """
                INSERT INTO contact_list (email, display_name, list_id, id)
                VALUES (?, ?, ?, ?)
            """.trimIndent()

            val statement = prepareStatement(query).apply {
                setString(1, contact.email)
                setString(2, contact.displayName)
                setString(3, list.id)
                setString(4, contact.id)
            }

            statement.executeUpdate()

            statement.close()
        }
    }

    private fun <T> execute(block: Connection.() -> T): T? {
        try {
            val connection = DriverManager.getConnection(jdbcUrl)
            val result = block(connection)
            connection.close()
            return result
        } catch (e: SQLException) {
            e.printStackTrace()
            return null
        }
    }
}