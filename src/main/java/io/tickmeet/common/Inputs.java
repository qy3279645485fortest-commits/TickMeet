package io.tickmeet.common;

import java.util.*;
import javax.validation.constraints.*;

/** API input DTOs intentionally contain no database IDs, role or order amounts. */
public final class Inputs {
  public static class Body {
    public Map<String, Object> map() {
      return Json.object(Json.write(this));
    }
  }

  public static class Email extends Body {
    @NotBlank
    @javax.validation.constraints.Email
    @Size(max = 254)
    public String email;
  }

  public static class Login extends Email {
    @Pattern(regexp = "[0-9]{6}")
    @NotNull
    public String code;
  }

  public static class Venue extends Body {
    @NotBlank
    @Size(max = 150)
    public String name;

    @NotBlank
    @Size(max = 60)
    public String city;

    @NotBlank
    @Size(max = 300)
    public String address;

    @NotNull public Double longitude, latitude;

    @Size(max = 1000)
    public String description;

    @Size(max = 9)
    public List<String> imageFileIds = new ArrayList<>();
  }

  public static class Event extends Body {
    @NotBlank
    @Size(max = 180)
    public String title;

    @NotBlank public String categoryId, venueId;

    @NotBlank
    @Size(max = 20000)
    public String description;

    public String coverFileId = "";

    @Size(max = 9)
    public List<String> imageFileIds = new ArrayList<>();

    @NotNull public Boolean refundAllowed;
    public String refundDeadlineAt;
  }

  public static class Session extends Body {
    @NotBlank
    @Size(max = 120)
    public String name;

    @NotBlank public String startAt, endAt, entryStartAt, entryEndAt;

    @NotNull
    @Min(1)
    @Max(1000000)
    public Integer capacity;
  }

  public static class TicketType extends Body {
    @NotBlank
    @Size(max = 80)
    public String name;

    @NotNull
    @Min(0)
    @Max(100000000)
    public Long priceCent;

    @NotNull
    @Min(1)
    @Max(1000000)
    public Integer stockTotal;

    @NotBlank public String saleStartAt, saleEndAt;
  }

  public static class Sale extends Body {
    @Pattern(regexp = "ON_SALE|PAUSED")
    @NotNull
    public String status;
  }

  public static class Post extends Body {
    @NotBlank public String eventId;

    @NotBlank
    @Size(max = 180)
    public String title;

    @NotBlank
    @Size(max = 10000)
    public String content;

    @Size(max = 9)
    public List<String> imageFileIds = new ArrayList<>();
  }

  public static class Like extends Body {
    @NotNull public Boolean liked;
  }

  public static class Follow extends Body {
    @NotNull public Boolean followed;
  }

  public static class Verification extends Body {
    @NotBlank
    @Size(max = 128)
    public String qrPayload;

    @NotBlank public String sessionId;
  }

  public static class Refund extends Body {
    @NotBlank
    @Size(max = 300)
    public String reason;
  }
}
