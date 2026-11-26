package nl.greaper.bnplanner.config

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoDatabase
import nl.greaper.bnplanner.auth.LoginFlow
import nl.greaper.bnplanner.auth.OsuLoginStore
import nl.greaper.bnplanner.auth.OsuTokens
import nl.greaper.bnplanner.auth.OsuUserService
import nl.greaper.bnplanner.service.AuthService
import nl.greaper.bnplanner.service.UserService
import org.mongodb.spring.session.config.annotation.web.http.EnableMongoHttpSession
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

/** Logging in with osu!, staying logged in through a session kept in MongoDB */
@Configuration
@EnableMongoHttpSession(collectionName = "sessions", maxInactiveIntervalInSeconds = LoginConfig.SESSION_SECONDS)
class LoginConfig {
    companion object {
        /** How long someone stays logged in without using the planner */
        const val SESSION_SECONDS = 60 * 60 * 24 * 30
    }

    @Bean
    fun clientRegistrations(osu: OsuProperties): ClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            ClientRegistration
                .withRegistrationId(OsuTokens.REGISTRATION_ID)
                .clientName("osu!")
                .clientId(osu.clientId)
                .clientSecret(osu.clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(osu.redirectUri.ifBlank { "{baseUrl}/login/oauth2/code/{registrationId}" })
                .scope("identify", "public")
                .authorizationUri("${osu.baseUrl}/oauth/authorize")
                .tokenUri("${osu.baseUrl}/oauth/token")
                .userInfoUri("${osu.baseUrl}/api/v2/me")
                .userNameAttributeName("id")
                .build(),
        )

    @Bean
    fun osuLoginStore(
        database: MongoDatabase,
        registrations: ClientRegistrationRepository,
    ): OAuth2AuthorizedClientService = OsuLoginStore(database, registrations)

    @Bean
    fun osuTokens(
        registrations: ClientRegistrationRepository,
        store: OAuth2AuthorizedClientService,
    ): OsuTokens {
        val manager = AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, store)
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().refreshToken().build())
        return OsuTokens(manager, store)
    }

    @Bean
    fun osuUserService(userService: UserService) = OsuUserService(userService)

    @Bean
    fun loginFlow(
        registrations: ClientRegistrationRepository,
        config: LoginProperties,
        userService: UserService,
        authService: AuthService,
    ) = LoginFlow(registrations, config, userService, authService)

    /** Only used by the sessions */
    @Bean
    fun mongoTemplate(
        client: MongoClient,
        config: MongoProperties,
    ) = MongoTemplate(client, config.db)

    /** Sent along by the website on bnplanner.greaper.net, which is the same site as the backend */
    @Bean
    fun cookieSerializer(config: LoginProperties): CookieSerializer =
        DefaultCookieSerializer().apply {
            setCookieName("PLANNER_SESSION")
            setSameSite("Lax")
            setUseHttpOnlyCookie(true)
            setUseSecureCookie(config.secureCookie)
        }
}
