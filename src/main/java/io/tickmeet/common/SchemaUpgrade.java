package io.tickmeet.common;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;

/** Non-destructive additions for existing local demo databases. */
@Component
public class SchemaUpgrade implements ApplicationRunner {
  private final Db db;

  public SchemaUpgrade(Db d) {
    db = d;
  }

  public void run(ApplicationArguments args) {
    Set<String> columns =
        db.jdbc.query(
            "SELECT * FROM tm_file WHERE 1=0",
            rs -> {
              Set<String> names = new HashSet<>();
              for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                names.add(rs.getMetaData().getColumnName(i).toLowerCase());
              return names;
            });
    if (!columns.contains("storage_provider"))
      db.update("ALTER TABLE tm_file ADD COLUMN storage_provider VARCHAR(20) DEFAULT 'local'");
    if (!columns.contains("storage_namespace"))
      db.update("ALTER TABLE tm_file ADD COLUMN storage_namespace VARCHAR(200) DEFAULT 'uploads'");
    index("tm_reservation", "idx_reservation_state_deadline", "state,deadline");
    index("tm_reservation", "idx_reservation_type", "ticket_type_id");
    index("tm_outbox", "idx_outbox_due", "status,next_at");
    index("tm_order", "idx_order_user", "user_id,created_at");
    index("tm_order", "idx_order_due", "status,expire_at");
    index("tm_session", "idx_session_event", "event_id");
    index("tm_ticket_type", "idx_type_session", "session_id");
    index("tm_follow", "idx_follow_target", "follow_user_id");
    index("tm_post", "idx_post_author", "user_id,created_at");
  }

  private void index(String table, String name, String cols) {
    Boolean exists =
        db.jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> {
                  try (java.sql.ResultSet rs =
                      connection
                          .getMetaData()
                          .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
                    while (rs.next())
                      if (name.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return true;
                    return false;
                  }
                });
    if (!Boolean.TRUE.equals(exists))
      db.update("CREATE INDEX " + name + " ON " + table + "(" + cols + ")");
  }
}
