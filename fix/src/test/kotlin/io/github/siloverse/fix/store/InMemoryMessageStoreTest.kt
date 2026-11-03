package io.github.siloverse.fix.store

class InMemoryMessageStoreTest : MessageStoreContract() {
    override fun store(): MessageStore = InMemoryMessageStore()
}
