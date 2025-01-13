package net.rsprox.shared.property

public interface PropertyTreeFormatter {
    public val propertyFormatterCollection: PropertyFormatterCollection
    public fun format(property: RootProperty): List<String>
}
