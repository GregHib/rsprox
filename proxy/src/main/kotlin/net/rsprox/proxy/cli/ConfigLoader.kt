package net.rsprox.proxy.cli

import java.io.File

public object ConfigLoader {
    public fun loadRs3(name: String): Map<Int, String> {
        val text = File("${System.getProperty("user.home")}/Documents/RSPS/kris/rs3/$name.txt")
        if (!text.exists()) {
            val file = File("${System.getProperty("user.home")}/Documents/RSPS/kris/rs3/$name.json")
            text.writeText(file.readText().substringAfter("\"entries\":{\"")
                .removeSuffix("\"}}")
                .replace("\":\"", "\t")
                .replace("\",\"", "\n"))
        }
        val map = mutableMapOf<Int, String>()
        for (line in text.readLines()) {
            if (line.isBlank()) {
                continue
            }
            val (int, string) = line.split("\t")
            map[int.toInt()] = string
        }
        return map
    }

    public fun loadRs3Map(name: String): Map<String, String> {
        val text = File("${System.getProperty("user.home")}/Documents/RSPS/kris/rs3/$name.txt")
        if (!text.exists()) {
            val file = File("${System.getProperty("user.home")}/Documents/RSPS/kris/rs3/$name.json")
            text.writeText(file.readText().substringAfter("\"entries\":{\"")
                .removeSuffix("\"}}")
                .replace("\":\"", "\t")
                .replace("\",\"", "\n"))
        }
        val map = mutableMapOf<String, String>()
        for (line in text.readLines()) {
            if (line.isBlank()) {
                continue
            }
            val (key, string) = line.split("\t")
            map[key] = string
        }
        return map
    }

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

    public fun loadReal(name: String, leak: String): Map<Int, String> {
        val file = File("${System.getProperty("user.home")}/Documents/Void/data/$leak/$name.txt")
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

    public fun loadRealMap(name: String, leak: String): Map<String, String> {
        val file = File("${System.getProperty("user.home")}/Documents/Void/data/$leak/$name.txt")
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
