package com.exam.model;

import com.exam.model.exam.Category;
import com.exam.model.exam.Providers;
import com.exam.model.exam.Quiz;
import com.exam.model.exam.Report;
import com.exam.token.Token;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user")

@Setter
@Getter
public class User implements UserDetails {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
//  @SequenceGenerator(name = "user_seq", sequenceName = "user_seq", allocationSize = 1)
  private Long id;
  @Column( nullable = false)
  private String firstname;
  @Column(nullable = false)
  private String lastname;
  @Column(unique = true, nullable = false)
  private String email;
  private String password;
  @Column(unique = true, nullable = false)
  private String username;
  private String phone;
  private boolean emailVerified = false;
  private boolean phoneVerified = false;
  private boolean enabled = true;
  @Column(length = 1000)
  private String profilePic;
  @Enumerated(EnumType.STRING)
  private Role role;
  @Enumerated(value = EnumType.STRING)
  private Providers provider = Providers.SELF;
  private String providerUserId;
  @Column(length = 1000)
  private String about;

//Trying to check for one quiz attempts

  @OneToMany(mappedBy = "user",cascade = CascadeType.ALL, orphanRemoval = true)
  @JsonIgnore
  private Set<Report> reports = new LinkedHashSet<>();
  @JsonIgnore
  @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Token> tokens = new ArrayList<>(); // ✅ Initialize to avoid null

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<Quiz> quizzes = new HashSet<>();


    // 🔹 CATEGORIES MAPPED TO USER
    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private Set<Category> categories = new HashSet<>();

  @Override
//  @JsonIgnore
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role.name()));
  }


    public void setPassword(String password) {
        this.password = password;
    }

    @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public void setEmailVerified(boolean emailVerified) {
    this.emailVerified = emailVerified;
  }

  public boolean isPhoneVerified() {
    return phoneVerified;
  }

  public void setPhoneVerified(boolean phoneVerified) {
    this.phoneVerified = phoneVerified;
  }

  public String getProfilePic() {
    return profilePic;
  }

  public void setProfilePic(String profilePic) {
    this.profilePic = profilePic;
  }

  public Providers getProvider() {
    return provider;
  }

  public void setProvider(Providers provider) {
    this.provider = provider;
  }

  public String getProviderUserId() {
    return providerUserId;
  }

  public void setProviderUserId(String providerUserId) {
    this.providerUserId = providerUserId;
  }

  public String getAbout() {
    return about;
  }

  public void setAbout(String about) {
    this.about = about;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
    public String getFullName() {
        return firstname + " " + lastname;
    }


    public String getFirstname() {
        return firstname;
    }

    public void setFirstname(String firstname) {
        this.firstname = firstname;
    }

    public String getLastname() {
        return lastname;
    }

    public void setLastname(String lastname) {
        this.lastname = lastname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
