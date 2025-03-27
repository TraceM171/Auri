package com.auri.app

import com.auri.app.analysis.AnalysisService
import com.auri.app.common.data.entity.RawSampleTable
import com.auri.app.common.data.entity.SampleInfoTable
import com.auri.app.common.data.entity.SampleLivenessCheckTable
import com.auri.app.common.data.sqliteConnection
import com.auri.extensions.analysis.FileChangeAnalyzer
import com.auri.extensions.analysis.SSHVMInteraction
import com.auri.extensions.analysis.VirtualBoxVMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.net.InetAddress
import java.net.URI
import kotlin.io.path.Path

fun main(): Unit = runBlocking {
    val baseDirectory = File("/home/auri/TFM/auri/scratch/.auri")
    val auriDB = File(baseDirectory, "auri.db").let(::sqliteConnection)

    newSuspendedTransaction(context = Dispatchers.IO, db = auriDB) {
        SchemaUtils.create(
            RawSampleTable,
            SampleInfoTable,
            SampleLivenessCheckTable
        )
    }

    val manager = VirtualBoxVMManager(
        definition = VirtualBoxVMManager.Definition(
            vmName = "win10",
            vmSnapshot = "SampleReady",
            endpoint = URI.create("http://192.168.56.4:18083").toURL(),
        )
    )
    val interaction = SSHVMInteraction(
        definition = SSHVMInteraction.Definition(
            host = InetAddress.getByName("192.168.56.7"),
            username = """desktop-mvt20j7\josef""",
            password = "josefina4891"
        )
    )
    val fileChangeAnalyzer = FileChangeAnalyzer(
        definition = FileChangeAnalyzer.Definition(
            files = listOf(
                FileChangeAnalyzer.VMFilePath("""C:\Users\josef\Downloads\sample.txt"""),
                FileChangeAnalyzer.VMFilePath("""C:\Users\josef\Downloads\sampleFake.txt""")
            )
        )
    )
    val analyzers = listOf(fileChangeAnalyzer)
    val analysisService = AnalysisService(
        cacheDir = File(""),
        samplesDir = baseDirectory.toPath().resolve("samples"),
        auriDB = auriDB,
        vmManager = manager,
        vmInteraction = interaction,
        analyzers = analyzers,
        sampleExecutionPath = Path("C:/Users/josef/Downloads/sample.exe")
    )
    launch {
        analysisService.run()
    }
    analysisService.analysisStatus.collect { status ->
        println("NEW STATUS! --> $status")
    }
}
