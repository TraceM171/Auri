package com.auri.extensions

import kotlinx.coroutines.runBlocking


fun main(): Unit = runBlocking {


    /*manager.launchVM().onLeft {
        println(it)
        return@runBlocking
    }
    println("Launched VM")
    val interaction = SSHVMInteraction(
        definition = SSHVMInteraction.Definition(
            host = InetAddress.getByName("192.168.56.7"),
            username = """desktop-mvt20j7\josef""",
            password = "josefina4891"
        )
    )
    interaction.sendFile(
        source = File("/home/auri/TFM/tmp/test.exe").inputStream().buffered(),
        remotePath = Path("""C:/Users/josef/Downloads/test2.exe""")
    ).getOrElse {
        println(it)
        return@runBlocking
    }
    println("Sent file")
    interaction.prepareCommand("""schtasks /CREATE /SC ONCE /TN "LaunchSample" /TR "C:\Users\josef\Downloads\test2.exe" /ST 00:00 /RL HIGHEST /F""")
        .run().onLeft {
            return@runBlocking
        }
    interaction.prepareCommand("""schtasks /RUN /TN "LaunchSample"""")
        .run().onLeft {
            return@runBlocking
        }
    println("Ran file")
    delay(30.seconds)
    manager.stopVM().onLeft {
        println(it)
        return@runBlocking
    }
    println("Stopped VM")*/

    /*val interaction = SSHVMInteraction(
        definition = SSHVMInteraction.Definition(
            host = InetAddress.getByName("192.168.56.7"),
            username = """desktop-mvt20j7\josef""",
            password = "josefina4891"
        )
    )

    val analyzer = FileChangeAnalyzer(
        definition = FileChangeAnalyzer.Definition(
            files = listOf(
                FileChangeAnalyzer.VMFilePath("""C:\Users\josef\Downloads\sample.txt"""),
                FileChangeAnalyzer.VMFilePath("""C:\Users\josef\Downloads\sampleFake.txt""")
            )
        )
    )
    Logger.i { "Capturing initial state" }
    analyzer.captureInitialState(
        File(""),
        interaction
    )
    Logger.i { "Make your changes!" }
    delay(1.minutes)
    Logger.i { "Reporting changes" }
    val hasChanged = analyzer.reportChanges(
        File(""),
        interaction
    ).getOrElse {
        println(it)
        return@runBlocking
    }
    if (!hasChanged) {
        Logger.i { "No changes detected" }
    } else {
        Logger.i { "Changes detected" }
    }*/
}
