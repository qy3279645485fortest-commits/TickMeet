package io.tickmeet.auth;

import com.baomidou.mybatisplus.annotation.*;

@TableName("tm_user")
public class UserEntity {
  @TableId(type = IdType.INPUT)
  private String id;

  private String email, nickName, icon, role;
  private Long createdAt;

  public String getId() {
    return id;
  }

  public void setId(String v) {
    id = v;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String v) {
    email = v;
  }

  public String getNickName() {
    return nickName;
  }

  public void setNickName(String v) {
    nickName = v;
  }

  public String getIcon() {
    return icon;
  }

  public void setIcon(String v) {
    icon = v;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String v) {
    role = v;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long v) {
    createdAt = v;
  }
}
