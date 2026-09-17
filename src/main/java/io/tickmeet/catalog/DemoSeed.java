package io.tickmeet.catalog;

import io.tickmeet.common.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoSeed implements ApplicationRunner {
  @org.springframework.beans.factory.annotation.Autowired
  private io.tickmeet.trade.Inventory inventory;

  private final Db db;
  private final boolean seed;

  public DemoSeed(Db d, @Value("${tickmeet.seed:false}") boolean s) {
    db = d;
    seed = s;
  }

  public void run(ApplicationArguments a) {
    if (!seed || db.count("SELECT COUNT(*) FROM tm_category") > 0) return;
    String[] categories = {"漫展次元", "艺术展览", "音乐现场", "城市体验"};
    for (int i = 0; i < 4; i++)
      db.update("INSERT INTO tm_category VALUES(?,?,?)", "cat" + i, categories[i], i);
    db.update(
        "INSERT INTO tm_user(id,email,nick_name,role,created_at) VALUES('demo-admin','admin@tickmeet.local','TickMeet 运营','ADMIN',?)",
        System.currentTimeMillis());
    db.update(
        "INSERT INTO tm_user(id,email,nick_name,role,created_at) VALUES('demo-staff','staff@tickmeet.local','入场核销员','STAFF',?)",
        System.currentTimeMillis());
    db.update(
        "INSERT INTO tm_user(id,email,nick_name,role,created_at) VALUES('demo-user','hello@tickmeet.local','现场体验官','USER',?)",
        System.currentTimeMillis());
    String[] titles = {"异次元集结 · ACG创想祭", "看见光的形状", "城市回声 · 独立音乐夜", "周末造物局"};
    String[] desc = {
      "把热爱从屏幕带到现场。独立创作者、限定周边与同好相遇，一起开启次元之外的周末。",
      "走进光与影交织的空间，在流动的色彩中寻找属于你的片刻。沉浸式装置与新媒体艺术展。",
      "三组独立乐队，一座不眠城市。让旋律替你说话，和喜欢的声音见一面。",
      "放下日常的进度条，亲手做点不一样的东西。陶艺、版画与手作工作坊，等一个有趣的你。"
    };
    String[] themes = {"violet", "lime", "orange", "blue"};
    long now = System.currentTimeMillis();
    for (int i = 0; i < 4; i++) {
      String v = "venue" + i, e = "event" + i, s = "session" + i;
      db.update(
          "INSERT INTO tm_venue VALUES(?,?,?,?,?,?,?,?)",
          v,
          new String[] {"西岸穹顶艺术中心", "光合美术馆", "回声Livehouse", "创意工场"}[i],
          "上海",
          "徐汇区龙腾大道" + (2000 + i * 30) + "号",
          121.46 + i * .002,
          31.18 + i * .002,
          "近地铁站，步行约8分钟",
          "[]");
      db.update(
          "INSERT INTO tm_event(id,category_id,venue_id,title,description,cover,images,status,refund_allowed,refund_deadline,theme,created_at) VALUES(?,?,?,?,?,?,?,'PUBLISHED',TRUE,?,?,?)",
          e,
          "cat" + i,
          v,
          titles[i],
          desc[i],
          "",
          "[]",
          now + 86400000L * 10,
          themes[i],
          now - i * 1000);
      long start = now + 86400000L * (i + 2);
      db.update(
          "INSERT INTO tm_session VALUES(?,?,?,?,?,?,?,?)",
          s,
          e,
          "周末限定场",
          start,
          start + 8 * 3600000L,
          now - 3600000L,
          start + 7 * 3600000L,
          300);
      db.update("INSERT INTO tm_staff_session VALUES('demo-staff',?)", s);
      for (int j = 0; j < 2; j++) {
        String t = "type" + i + "_" + j;
        db.update(
            "INSERT INTO tm_ticket_type VALUES(?,?,?,?,?,?,?,?)",
            t,
            s,
            j == 0 ? "限量早鸟票" : "标准通行票",
            new int[] {6800, 8800, 12800, 5800}[i] + j * 3000,
            j == 0 ? 100 : 200,
            now - 3600000L,
            start - 60000,
            "ON_SALE");
        db.update("INSERT INTO tm_stock VALUES(?,?)", t, j == 0 ? 100 : 200);
        inventory.initialize(t, j == 0 ? 100 : 200);
      }
    }
  }
}
