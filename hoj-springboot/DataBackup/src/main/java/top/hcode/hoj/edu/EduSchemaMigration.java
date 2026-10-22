package top.hcode.hoj.edu;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;

/** Additive, checksummed migrations; MySQL DDL is deliberately not treated as transactional. */
@Component
public class EduSchemaMigration implements ApplicationRunner {
    private final DataSource dataSource;
    public EduSchemaMigration(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String lock = "hoj_edu_" + connection.getCatalog();
            boolean locked = false;
            try {
                try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                    statement.setString(1, lock);
                    try (ResultSet result = statement.executeQuery()) {
                        locked = result.next() && result.getInt(1) == 1;
                    }
                }
                if (!locked) throw new IllegalStateException("Could not acquire education migration lock");
                migrate(connection);
            } finally {
                if (locked) try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    statement.setString(1, lock);
                    statement.execute();
                }
            }
        }
    }

    private void migrate(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS edu_schema_history (version VARCHAR(64) PRIMARY KEY, " +
                    "checksum CHAR(64) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB");
        }
        for (String version : new String[]{"V001__class_members", "V002__shared_courses", "V003__course_icons"}) apply(connection, version);
    }

    private void apply(Connection connection, String version) throws Exception {
        byte[] bytes;
        try (java.io.InputStream input = new ClassPathResource("db/edu/" + version + ".sql").getInputStream()) {
            bytes = StreamUtils.copyToByteArray(input);
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value & 255));
        try (PreparedStatement statement = connection.prepareStatement("SELECT checksum FROM edu_schema_history WHERE version=?")) {
            statement.setString(1, version);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!digest.toString().equals(result.getString(1)))
                        throw new IllegalStateException("Applied education migration checksum differs: " + version);
                    return;
                }
            }
        }
        // Match the actual existing UUID collation, including databases restored from older releases.
        String charset, collation;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_info' AND COLUMN_NAME='uuid'")) {
            if (!result.next()) throw new IllegalStateException("Existing user_info.uuid is required");
            charset = result.getString(1); collation = result.getString(2);
        }
        if (!charset.matches("[a-zA-Z0-9_]+") || !collation.matches("[a-zA-Z0-9_]+"))
            throw new IllegalStateException("Unexpected UUID character set");
        String sql = new String(bytes, StandardCharsets.UTF_8).replace("${userCharset}", charset).replace("${userCollation}", collation);
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        if ("V001__class_members".equals(version)) try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM role WHERE role='teacher'")) {
            result.next();
            if (result.getInt(1) > 1) throw new IllegalStateException("Duplicate teacher roles require review");
            if (result.getInt(1) == 0) try (Statement insert = connection.createStatement()) {
                insert.executeUpdate("INSERT INTO role(id,role,description,status) SELECT COALESCE(MAX(id),0)+1,'teacher','教学教师',0 FROM role");
            }
        }
        // Validate partial-retry tables before recording success. No existing business rows are replaced.
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT id,name,description,owner_uid,status,gmt_create,gmt_modified FROM edu_class LIMIT 0").close();
            statement.executeQuery("SELECT id,class_id,uid,role,status,gmt_create,gmt_modified FROM edu_class_member LIMIT 0").close();
            if ("V002__shared_courses".equals(version)) {
                statement.executeQuery("SELECT id,title,description,owner_uid,status,revision FROM edu_course LIMIT 0").close();
                statement.executeQuery("SELECT id,class_id,course_id,sort_order,created_by FROM edu_class_course LIMIT 0").close();
                statement.executeQuery("SELECT id,course_id,pid,sort_order FROM edu_course_problem LIMIT 0").close();
            }
            if ("V003__course_icons".equals(version)) statement.executeQuery("SELECT course_id,filename,gmt_modified FROM edu_course_icon LIMIT 0").close();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO edu_schema_history(version,checksum) VALUES (?,?)")) {
            statement.setString(1, version); statement.setString(2, digest.toString()); statement.executeUpdate();
        }
    }
}
