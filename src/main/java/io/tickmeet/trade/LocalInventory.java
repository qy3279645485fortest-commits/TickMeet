package io.tickmeet.trade;

import io.tickmeet.common.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"demo", "test"})
public class LocalInventory implements Inventory {
  private final Db db;
  private final Map<String, Integer> stock = new HashMap<>();
  private final Map<String, String> holders = new HashMap<>();
  private final Map<String, String> states = new HashMap<>();

  public LocalInventory(Db d) {
    db = d;
  }

  public synchronized String hold(Map<String, Object> t, String user, String r) {
    if (states.containsKey(r)) return states.get(r).equals("HELD") ? "OK" : states.get(r);
    String id = Db.s(t, "id");
    long now = System.currentTimeMillis();
    if (!"ON_SALE".equals(t.get("status"))) return "SALE_PAUSED";
    if (now < Db.n(t, "sale_start")) return "SALE_NOT_STARTED";
    if (now >= Db.n(t, "sale_end")) return "SALE_ENDED";
    if (holders.containsKey(id + ":" + user)) return "PURCHASE_LIMIT";
    if (!stock.containsKey(id))
      stock.put(id, (int) db.count("SELECT available FROM tm_stock WHERE ticket_type_id=?", id));
    int n = stock.get(id);
    if (n <= 0) return "SOLD_OUT";
    stock.put(id, n - 1);
    holders.put(id + ":" + user, r);
    states.put(r, "HELD");
    return "OK";
  }

  public synchronized void release(String type, String user, String r) {
    if (!"HELD".equals(states.get(r))) return;
    states.put(r, "RELEASED");
    stock.put(type, stock.getOrDefault(type, 0) + 1);
    holders.remove(type + ":" + user, r);
  }
}
