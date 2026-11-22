package io.github.siloverse.brokerage.ledger

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc

/**
 * Every ledger integration test extends this. The Spring setup lives here once, so all of
 * them share one started application and one PostgreSQL container.
 *
 * Tests do not clean up after themselves: each one works with its own random user and its
 * own keys, so they cannot disturb each other and may run in any order.
 */
@SpringBootTest(classes = [LedgerTestApplication::class])
@AutoConfigureMockMvc
abstract class BaseIntegrationTest {

    @Autowired
    protected lateinit var mvc: MockMvc

    @Autowired
    @Qualifier("ledgerJdbc")
    protected lateinit var jdbc: JdbcClient
}
