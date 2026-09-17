package io.tickmeet;

import io.tickmeet.auth.*;
import io.tickmeet.catalog.*;
import io.tickmeet.common.*;
import java.util.*;

public class TradeFixture {
  public static String type(CatalogService c, int stock) {
    Db db = c.database();
    CurrentUser.set(Api.map("id", "fixture-admin", "role", "ADMIN"));
    String cat = Db.id();
    db.update("INSERT INTO tm_category VALUES(?,?,1)", cat, "演出");
    String venue =
        (String)
            c.saveVenue(
                    null,
                    Api.map(
                        "name",
                        "城市艺术馆",
                        "city",
                        "上海",
                        "address",
                        "测试路",
                        "longitude",
                        121.47,
                        "latitude",
                        31.23))
                .get("id");
    String event =
        (String)
            c.saveEvent(
                    null,
                    Api.map(
                        "title",
                        "并发测试活动",
                        "categoryId",
                        cat,
                        "venueId",
                        venue,
                        "description",
                        "测试",
                        "refundAllowed",
                        true,
                        "refundDeadlineAt",
                        java.time.OffsetDateTime.now().plusHours(6).toString()))
                .get("id");
    String session =
        (String) c.saveSession(event, null, CatalogModuleTest.sessionInput(stock)).get("id");
    String type = (String) c.saveType(session, null, CatalogModuleTest.typeInput(stock)).get("id");
    c.publish(event);
    CurrentUser.clear();
    return type;
  }
}
