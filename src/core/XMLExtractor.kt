package core

/**
 * Author: Edward Liu
 */

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.lang.IndexOutOfBoundsException
import java.lang.StringBuilder

/**
 * Goes through the user-highlighted xml file and instantiates all UI elements in the current file.
 * xml file must be a layout xml file
 */
class XmlExtractor: AnAction() {

    private lateinit var editor: Editor
    private lateinit var project: Project
    private lateinit var document: Document
    private lateinit var userSelection: String
    private lateinit var virtualFile: VirtualFile
    private lateinit var actionEvent: AnActionEvent

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        var enablePlugin = false
        if (editor != null && project != null) enablePlugin = editor.caretModel.allCarets.isNotEmpty()
        e.presentation.isEnabledAndVisible = enablePlugin
    }

    override fun actionPerformed(e: AnActionEvent) {
        actionEvent = e
        userSelection = ""
        editor = e.getRequiredData(CommonDataKeys.EDITOR)
        document = editor.document
        val hasSelection = editor.selectionModel.hasSelection()
        if (!hasSelection) notifyUser("Nothing Highlighted", "Highlight an XML file name with your cursor before using the shortcut.")
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
        val xmlFactoryObject = XmlPullParserFactory.newInstance()
        val myParser = xmlFactoryObject.newPullParser()
        virtualFile = xmlFile.elementAt(0).virtualFile
        myParser.setInput(virtualFile.inputStream, null)
        val typeToIdMap = getTypeAndIdMap(myParser)
        generateInstantiations(typeToIdMap)
    }

    private fun verifyFile(xmlFile: Array<out PsiFile>): Boolean {
        return try {
            xmlFile.elementAt(0)
            true
        } catch (exception: IndexOutOfBoundsException) {
            notifyUser("File not found",
                    "The characters you highlighted do not match the names of any files in your project. " +
                            "Highlight the entire xml file name without the '.xml' extension.")
            false
        }
    }

    private fun getTypeAndIdMap(myParser: XmlPullParser): MutableMap<String, String> {
        val typeToIdMap = mutableMapOf<String, String>()
        var currentEvent = myParser.eventType
        while (currentEvent != XmlPullParser.END_DOCUMENT) {
            val nameOfCurrentElement = myParser.name
            if (currentEvent == XmlPullParser.START_TAG) {
                val id = myParser.getAttributeValue(null, "android:id")
                if (id != null) {
                    val strippedId = id.drop(5)
                    typeToIdMap[nameOfCurrentElement] = strippedId
                }
            }
            currentEvent = myParser.next()
        }
        return typeToIdMap
    }

    private fun generateInstantiations(typeToIdMap: MutableMap<String, String>) {
        val currentPsiFile = actionEvent.getData(CommonDataKeys.PSI_FILE)
        val currentLanguage = currentPsiFile?.language?.displayName!!
        val caretModel = editor.caretModel
        caretModel.primaryCaret.selectLineAtCaret() //Move the cursor to the end of the line
        val indexToInsertStatements = caretModel.primaryCaret.selectionEnd
        when (currentLanguage) {
            "Java" -> generateJavaStatements(typeToIdMap, indexToInsertStatements)
            "Kotlin" -> generateKotlinStatements(typeToIdMap, indexToInsertStatements)
            else -> notifyUser("Language not Supported", "This plugin currently does not support $currentLanguage.")
        }
        caretModel.primaryCaret.removeSelection()
    }

    private fun generateJavaStatements(typeToIdMap: MutableMap<String, String>, indexToInsertStatements: Int) {
        val javaStatements = StringBuilder()
        javaStatements.append("\t/*\n")
        javaStatements.append("\tXML Extraction for: $userSelection\n\n")
        generateJavaSplits(typeToIdMap, javaStatements)
        generateJavaNonSplits(typeToIdMap, javaStatements)
        javaStatements.append("\t*/\n")
        writeToDocument(indexToInsertStatements, javaStatements)
    }

    private fun generateJavaSplits(typeToIdMap: MutableMap<String, String>, javaStatements: StringBuilder) {
        javaStatements.append("\t1) Split Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { javaStatements.append("\t\tprivate ${it.key} ${it.value};\n") }
        javaStatements.append("\n")
        typeToIdMap.forEach { javaStatements.append("\t\t${it.value} = findViewById(R.id.${it.value});\n") }
    }

    private fun generateJavaNonSplits(typeToIdMap: MutableMap<String, String>, javaStatements: StringBuilder) {
        javaStatements.append("\n\t2) Single line Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { javaStatements.append("\t\tprivate ${it.key} ${it.value} = findViewById(R.id.${it.value});\n") }
    }

    private fun generateKotlinStatements(typeToIdMap: MutableMap<String, String>, indexToInsertStatements: Int) {
        val kotlinStatements = StringBuilder()
        kotlinStatements.append("\t/*\n")
        kotlinStatements.append("\tXML Extraction for: $userSelection\n\n")
        generateKotlinSplits(typeToIdMap, kotlinStatements)
        generateKotlinNonSplits(typeToIdMap, kotlinStatements)
        if (usingKotlinAndroidExtensions()) generateXMLIds(typeToIdMap, kotlinStatements)
        kotlinStatements.append("\t*/\n")
        writeToDocument(indexToInsertStatements, kotlinStatements)
    }

    private fun usingKotlinAndroidExtensions(): Boolean {
        val buildGradleFile =  FilenameIndex.getFilesByName(project, "build.gradle", GlobalSearchScope.projectScope(project))
        return buildGradleFile.elementAt(0).text.contains("apply plugin: 'kotlin-android-extensions'")
    }

    private fun generateXMLIds(typeToIdMap: MutableMap<String, String>, kotlinStatements: StringBuilder) {
        kotlinStatements.append("\n\t3) 'kotlin-android-extensions' plugin detected." +
                " Here are all IDs of the XML elements in $userSelection:\n\n")
        typeToIdMap.forEach { kotlinStatements.append("\t\t${it.value}\n") }
    }

    private fun generateKotlinSplits(typeToIdMap: MutableMap<String, String>, kotlinStatements: StringBuilder) {
        kotlinStatements.append("\t1) Split Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { kotlinStatements.append("\t\tprivate lateinit var ${it.value}: ${it.key}\n") }
        kotlinStatements.append("\n")
        typeToIdMap.forEach { kotlinStatements.append("\t\t${it.value} = findViewById(R.id.${it.value})\n") }
    }

    private fun generateKotlinNonSplits(typeToIdMap: MutableMap<String, String>, kotlinStatements: StringBuilder) {
        kotlinStatements.append("\n\t2) Single line Declaration & Instantiation:\n\n")
        typeToIdMap.forEach { kotlinStatements.append("\t\tval ${it.value} = findViewById<${it.key}>(R.id.${it.value});\n") }
    }

    private fun writeToDocument(index: Int, statements: StringBuilder) {
        WriteCommandAction.runWriteCommandAction(project) {document.insertString(index, statements.toString())}
    }
}