package dev.coachbot.web;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import dev.coachbot.web.views.LoginView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security + Vaadin integration.
 *
 * <p>All Vaadin views require authentication by default (via {@link VaadinWebSecurity}).
 * The login page is a Vaadin {@link LoginView}.
 * GET logout is allowed so a simple link/button can sign the user out.
 */
@EnableWebSecurity
@Configuration
public class WebSecurityConfig extends VaadinWebSecurity {

    private final WebProperties props;

    public WebSecurityConfig(WebProperties props) {
        this.props = props;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        // Allow GET /logout so the logout button works without a form+CSRF token
        http.logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));

        super.configure(http);          // VaadinWebSecurity must be called last
        setLoginView(http, LoginView.class);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        var user = User.withUsername(props.getUsername())
                .password("{noop}" + props.getPassword())
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
