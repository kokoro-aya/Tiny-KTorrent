package moe.irony.main

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.irony.client.TorrentClient
import java.nio.file.Files
import java.nio.file.Paths

const val PROGRAM_NAME = "./client"
const val LOG_PATH_KEY = "LOG_PATH"

fun main(args: Array<String>) {
    println("Tiny KTorrent - A simple multi-thread BitTorrent client written in Kotlin")
    val optionsParser = ArgParser(PROGRAM_NAME)
    val seed by optionsParser
        .option(ArgType.String, shortName = "s", description = "Path to the Torrent Seed file")
        .required()
    val output by optionsParser
        .option(ArgType.String, shortName = "o", description = "The output directory to which the file will be downloaded")
        .required()
    val threadnum by optionsParser
        .option(ArgType.Int, shortName = "n", description = "Number of downloading threads to use")
        .default(8)
    val logging by optionsParser
        .option(ArgType.Boolean, shortName = "l", description = "Enable logging")
        .default(false)
    val logfile by optionsParser
        .option(ArgType.String, shortName = "f", description = "Path to the log directory")
        .default("./logs/ktorrent")

    optionsParser.parse(args)

    System.setProperty(LOG_PATH_KEY, logfile)

    Files.createDirectories(Paths.get(output))

    val client = TorrentClient(
        workersNum = threadnum,
        enableLogging = logging,
    )
    runBlocking {
        client.downloadFile(
            torrentFilePath = seed,
            downloadDirectory = output
        )
    }
}