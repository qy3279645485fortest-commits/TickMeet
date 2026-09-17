package io.tickmeet.community;

import io.tickmeet.auth.*;
import io.tickmeet.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CommunityController {
  private final CommunityService s;

  public CommunityController(CommunityService c) {
    s = c;
  }

  String optionalUser() {
    try {
      return CurrentUser.id();
    } catch (Problem p) {
      return null;
    }
  }

  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/posts")
  public Object post(@javax.validation.Valid @RequestBody Inputs.Post b) {
    return Api.ok(s.post(CurrentUser.id(), b.map()));
  }

  @PutMapping("/posts/{id}/like")
  public Object like(@PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Like b) {
    return Api.ok(s.like(CurrentUser.id(), id, Api.bool(b.map(), "liked")));
  }

  @GetMapping("/posts/{id}/likes")
  public Object likes(@PathVariable String id) {
    return Api.ok(s.likes(id));
  }

  @GetMapping("/posts/mine")
  public Object mine(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
    return Api.ok(s.list(CurrentUser.id(), CurrentUser.id(), null, page, size));
  }

  @GetMapping("/users/{id}/posts")
  public Object userPosts(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(s.list(optionalUser(), id, null, page, size));
  }

  @GetMapping("/posts/feed")
  public Object feed(
      @RequestParam(required = false) Long lastId, @RequestParam(defaultValue = "0") int offset) {
    return Api.ok(
        s.feed(CurrentUser.id(), lastId == null ? System.currentTimeMillis() : lastId, offset));
  }

  @GetMapping("/posts/hot")
  public Object hot(
      @RequestParam(required = false) String eventId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int size) {
    return Api.ok(s.list(optionalUser(), null, eventId, page, size));
  }

  @GetMapping("/posts/{id}")
  public Object get(@PathVariable String id) {
    return Api.ok(s.get(id, optionalUser()));
  }

  @GetMapping("/follows/{id}")
  public Object follows(@PathVariable String id) {
    return Api.ok(Api.map("followed", s.follows(CurrentUser.id(), id)));
  }

  @PutMapping("/follows/{id}")
  public Object follow(
      @PathVariable String id, @javax.validation.Valid @RequestBody Inputs.Follow b) {
    return Api.ok(s.follow(CurrentUser.id(), id, Api.bool(b.map(), "followed")));
  }

  @GetMapping("/follows/common/{id}")
  public Object common(@PathVariable String id) {
    return Api.ok(s.common(CurrentUser.id(), id));
  }

  @PostMapping("/user/sign")
  public Object sign() {
    s.sign(CurrentUser.id());
    return Api.ok(null);
  }

  @GetMapping("/user/sign/count")
  public Object count() {
    return Api.ok(s.countSign(CurrentUser.id()));
  }
}
