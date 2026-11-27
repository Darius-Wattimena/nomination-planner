package nl.greaper.bnplanner.config

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import nl.greaper.bnplanner.auth.LoginFlow
import nl.greaper.bnplanner.auth.OsuLoginResolver
import nl.greaper.bnplanner.auth.OsuTokens
import nl.greaper.bnplanner.auth.OsuUserService
import nl.greaper.bnplanner.auth.SessionRolesFilter
import nl.greaper.bnplanner.service.UserService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.context.SecurityContextHolderFilter
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val userService: UserService,
    private val loginFlow: LoginFlow,
    private val osuUserService: OsuUserService,
    private val planner: PlannerProperties,
) {
    companion object {
        /** The header the website sends the token against cross-site requests in, it gets it from /v2/session */
        const val CSRF_HEADER = "X-CSRF-TOKEN"
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .cors { }
            .csrf { configurer ->
                configurer
                    .csrfTokenRepository(HttpSessionCsrfTokenRepository().apply { setHeaderName(CSRF_HEADER) })
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                    // Without a session there is nothing to protect, which leaves answering 401 to the login check
                    .ignoringRequestMatchers({ request: HttpServletRequest -> request.getSession(false) == null })
            }.exceptionHandling { configurer ->
                configurer.authenticationEntryPoint { _: HttpServletRequest?, response: HttpServletResponse, ex: AuthenticationException ->
                    response.sendError(
                        HttpServletResponse.SC_UNAUTHORIZED,
                        ex.message,
                    )
                }
            }.oauth2Login { login ->
                login
                    .authorizationEndpoint { it.authorizationRequestResolver(loginFlow) }
                    .userInfoEndpoint { it.userService(osuUserService) }
                    .successHandler(loginFlow)
                    .failureHandler(loginFlow)
            }.logout { logout ->
                logout
                    .logoutUrl("/v2/logout")
                    .logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
            }.authorizeHttpRequests { registry ->
                registry
                    // Answering a request that waited, which was checked when it came in
                    .dispatcherTypeMatchers(DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers("/v2/session", "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.addFilterAfter(SessionRolesFilter(userService, planner.admins), SecurityContextHolderFilter::class.java)
            .build()

    @Bean
    fun corsConfigurationSource(cors: CorsProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = cors.uris.split(",")
        configuration.allowedMethods = cors.methods.split(",")
        configuration.allowedHeaders = cors.headers.split(",")
        // The website sends the login cookie along
        configuration.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun osuLoginArguments(tokens: OsuTokens) =
        object : WebMvcConfigurer {
            override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
                resolvers.add(OsuLoginResolver(tokens))
            }
        }
}
