package net.rsprox.transcriber.state

public class Inventory {
    public data class Item(val id: Int, val amount: Int)
    public val items: MutableMap<Int, Item> = mutableMapOf()
}
