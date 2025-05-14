package net.rsprox.proxy.cli

import java.io.File

public object ConfigLoader {
    public fun loadOsrs(name: String): Map<Int, String> {
        val file = File("${System.getProperty("user.home")}/Documents/RSPS/kris/mappings/$name.rscm")
        val map = mutableMapOf<Int, String>()
        for (line in file.readLines()) {
            if (line.isBlank()) {
                continue
            }
            val (string, int) = line.split(":")
            map[int.toInt()] = string
        }
        return map
    }

    public fun loadReal(name: String): Map<Int, String> {
        val file = File("${System.getProperty("user.home")}/Documents/Void/data/leak-2025-04/$name.txt")
        val map = mutableMapOf<Int, String>()
        for (line in file.readLines()) {
            if (line.isBlank()) {
                continue
            }
            val (int, string) = line.split("\t")
            map[int.toInt()] = string
        }
        return map
    }

    public fun loadRealMap(name: String): Map<String, String> {
        val file = File("${System.getProperty("user.home")}/Documents/Void/data/leak-2025-04/$name.txt")
        val map = mutableMapOf<String, String>()
        for (line in file.readLines()) {
            if (line.isBlank()) {
                continue
            }
            val (key, string) = line.split("\t")
            map[key] = string
        }
        return map
    }
}
