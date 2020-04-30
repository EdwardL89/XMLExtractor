/**
 * @author: Edward Liu
 * @version: 1.0.0
 */

package core

import java.lang.StringBuilder
import com.intellij.psi.PsiFile
import org.xmlpull.v1.XmlPullParser
import com.intellij.openapi.editor.Editor
import org.xmlpull.v1.XmlPullParserFactory
import java.lang.IndexOutOfBoundsException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.notification.NotificationDisplayType
import com.intellij.openapi.fileEditor.FileDocumentManager

private class XmlExtractor: AnAction() {

    private lateinit var editor: Editor
    private lateinit var project: Project
    private lateinit var document: Document
    private lateinit var userSelection: String
    private lateinit var virtualFile: VirtualFile
    private lateinit var actionEvent: AnActionEvent

    override fun update(e: AnActionEvent) {
        val project = e.project
        var enablePlugin = false
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null && project != null) enablePlugin = editor.caretModel.allCarets.isNotEmpty()
        e.presentation.isEnabledAndVisible = enablePlugin
    }

    override fun actionPerformed(e: AnActionEvent) {
        FileDocumentManager.getInstance().saveAllDocuments()
        actionEvent = e
        userSelection = ""
        editor = e.getRequiredData(CommonDataKeys.EDITOR)
        document = editor.document
        val hasSelection = editor.selectionModel.hasSelection()
        if (!hasSelection) notifyUser("XML Extractor", "Nothing highlighted: Highlight an XML file name with your cursor before using the shortcut.")
        else continueWithExtraction()
    }

    private fun continueWithExtraction() {
        val xmlFileName = getFileNameFromHighlightedText()
        val xmlFile = retrieveFileInstanceFromProject(xmlFileName)
        operateOnXmlFile(xmlFile)
    }

    private fun getFileNameFromHighlightedText(): String {
        val caretModel = editor.caretModel
        val selectedText = caretModel.currentCaret.selectedText!!
        userSelection = "$selectedText.xml"
        return userSelection
    }

    private fun retrieveFileInstanceFromProject(xmlFileName: String): Array<out PsiFile> {
        project = actionEvent.getRequiredData(CommonDataKeys.PROJECT)
        return FilenameIndex.getFilesByName(project, xmlFileName, GlobalSearchScope.projectScope(project))
    }

    private fun notifyUser(title: String, message: String) {
        val notification = NotificationGroup("XMLExtractor", NotificationDisplayType.BALLOON, true)
        notification.createNotification(title,
                message,
                NotificationType.INFORMATION,
                null).notify(actionEvent.project)
    }

    private fun operateOnXmlFile(xmlFile: Array<out PsiFile>) {
        if (!verifyFile(xmlFile)) return
        val myParser = XmlPullParserFactory.newInstance().newPullParser()
        virtualFile = xmlFile.elementAt(0).virtualFile
        myParser.setInput(virtualFile.inputStream, null)
        generateInstantiations(getTypeAndIdMap(myParser))
    }

    private fun verifyFile(xmlFile: Array<out PsiFile>): Boolean {
        return try {
            xmlFile.elementAt(0)
            true
        } catch (exception: IndexOutOfBoundsException) {
            notifyUser("XML Extractor",
                    "File not found: The text highlighted does not match any files in your project. " +
                            "Highlight the entire xml file name without the '.xml' extension.")
            false
        }
    }

    private fun getTypeAndIdMap(myParser: XmlPullParser): MutableList<Pair<String, String>> {
        val typeToIdMap = mutableListOf<Pair<String, String>>()
        var currentEvent = myParser.eventType
        while (currentEvent != XmlPullParser.END_DOCUMENT) {
            val nameOfCurrentElement = myParser.name
            if (currentEvent == XmlPullParser.START_TAG) {
                val id = myParser.getAttributeValue(null, "android:id")
                if (id != null) typeToIdMap.add(Pair(nameOfCurrentElement, id.drop(5)))
            }
            currentEvent = myParser.next()
        }
        return typeToIdMap
    }

    private fun generateInstantiations(typeToIdMap: MutableList<Pair<String, String>>) {
        val currentLanguage = actionEvent.getData(CommonDataKeys.PSI_FILE)?.language?.displayName!!
        val caretModel = editor.caretModel
        caretModel.primaryCaret.selectLineAtCaret() //Move the cursor to the end of the line
        val indexToInsertStatements = caretModel.primaryCaret.selectionEnd
        when (currentLanguage) {
            "Java" -> generateJavaStatements(typeToIdMap, indexToInsertStatements)
            "Kotlin" -> generateKotlinStatements(typeToIdMap, indexToInsertStatements)
            else -> notifyUser("XML Extractor", "Language not Supported: This plugin currently does not support $currentLanguage.")
        }
        caretModel.primaryCaret.removeSelection()
    }

    private fun generateJavaStatements(typeToIdMap: MutableList<Pair<String, String>>, indexToInsertStatements: Int) {
        val javaStatements = StringBuilder()
        javaStatements.append("\t/*\n\tXML Extraction for: $userSelection\n\n")
        generateJavaSplits(typeToIdMap, javaStatements)
        generateJavaNonSplits(typeToIdMap, javaStatements)
        javaStatements.append("\t*/\n")
        writeToDocument(indexToInsertStatements, javaStatements)
    }

    private fun generateJavaSplits(typeToIdMap: MutableList<Pair<String, String>>, javaStatements: StringBuilder) {
        val linesTobeSorted = mutableListOf<String>()
        javaStatements.append("\t1) Split Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { linesTobeSorted.add("\t\tprivate ${it.first} ${it.second};\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { javaStatements.append(it) }
        javaStatements.append("\n")
        linesTobeSorted.clear()
        typeToIdMap.forEach { linesTobeSorted.add("\t\t${it.second} = findViewById(R.id.${it.second});\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { javaStatements.append(it) }
    }

    private fun generateJavaNonSplits(typeToIdMap: MutableList<Pair<String, String>>, javaStatements: StringBuilder) {
        val linesTobeSorted = mutableListOf<String>()
        javaStatements.append("\n\t2) Single line Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { linesTobeSorted.add("\t\t${it.first} ${it.second} = findViewById(R.id.${it.second});\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { javaStatements.append(it) }
    }

    private fun generateKotlinStatements(typeToIdMap: MutableList<Pair<String, String>>, indexToInsertStatements: Int) {
        val kotlinStatements = StringBuilder()
        kotlinStatements.append("\t/*\n\tXML Extraction for: $userSelection\n\n")
        generateKotlinSplits(typeToIdMap, kotlinStatements)
        generateKotlinNonSplits(typeToIdMap, kotlinStatements)
        if (usingKotlinAndroidExtensions()) generateXMLIds(typeToIdMap, kotlinStatements)
        kotlinStatements.append("\t*/\n")
        writeToDocument(indexToInsertStatements, kotlinStatements)
    }

    private fun usingKotlinAndroidExtensions(): Boolean {
        val buildGradleFile =  FilenameIndex.getFilesByName(project, "build.gradle", GlobalSearchScope.projectScope(project))
        return testForPluginExistence(buildGradleFile)
    }

    private fun testForPluginExistence(buildGradleFile: Array<PsiFile>): Boolean {
        try {
            val firstFileHasAnko = buildGradleFile.elementAt(0).text.contains("apply plugin: 'kotlin-android-extensions'")
            if (firstFileHasAnko) return true
            val secondFileHasAnko = buildGradleFile.elementAt(1).text.contains("apply plugin: 'kotlin-android-extensions'")
            if (secondFileHasAnko) return true
        } catch (exception: IndexOutOfBoundsException) {
            return false
        }
        return false
    }

    private fun generateXMLIds(typeToIdMap: MutableList<Pair<String, String>>, kotlinStatements: StringBuilder) {
        val linesTobeSorted = mutableListOf<String>()
        kotlinStatements.append("\n\t3) 'kotlin-android-extensions' plugin detected. Here are all IDs of the XML elements in $userSelection:\n\n")
        typeToIdMap.forEach { linesTobeSorted.add("\t\t${it.second}\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { kotlinStatements.append(it) }
    }

    private fun generateKotlinSplits(typeToIdMap: MutableList<Pair<String, String>>, kotlinStatements: StringBuilder) {
        val linesTobeSorted = mutableListOf<String>()
        kotlinStatements.append("\t1) Split Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { linesTobeSorted.add("\t\tprivate lateinit var ${it.second}: ${it.first}\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { kotlinStatements.append(it) }
        kotlinStatements.append("\n")
        linesTobeSorted.clear()
        typeToIdMap.forEach { linesTobeSorted.add("\t\t${it.second} = findViewById(R.id.${it.second})\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { kotlinStatements.append(it) }
    }

    private fun generateKotlinNonSplits(typeToIdMap: MutableList<Pair<String, String>>, kotlinStatements: StringBuilder) {
        val linesTobeSorted = mutableListOf<String>()
        kotlinStatements.append("\n\t2) Single line Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { linesTobeSorted.add("\t\tval ${it.second} = findViewById<${it.first}>(R.id.${it.second});\n") }
        linesTobeSorted.sortedBy { line -> line.length }.forEach { kotlinStatements.append(it) }
    }

    private fun writeToDocument(index: Int, statements: StringBuilder) {
        WriteCommandAction.runWriteCommandAction(project) {document.insertString(index, statements.toString())}
    }
}