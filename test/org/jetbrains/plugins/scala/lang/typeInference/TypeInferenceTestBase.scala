package org.jetbrains.plugins.scala
package lang
package typeInference

import java.io.File

import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.{CharsetToolkit, VfsUtil}
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightPlatformTestCase
import org.jetbrains.plugins.scala.base.ScalaLightPlatformCodeInsightTestCaseAdapter
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.SyntheticMembersInjector
import org.jetbrains.plugins.scala.lang.psi.types.api.Unit
import org.jetbrains.plugins.scala.lang.psi.types.api.ScTypePresentation
import org.jetbrains.plugins.scala.lang.psi.types.result.{Failure, Success, TypingContext}
import org.jetbrains.plugins.scala.util.TestUtils
import org.junit.Assert._

/**
  * User: Alexander Podkhalyuzin
  * Date: 10.03.2009
  */

abstract class TypeInferenceTestBase extends ScalaLightPlatformCodeInsightTestCaseAdapter with TypeInferenceDoTest {
  protected def folderPath: String = TestUtils.getTestDataPath + "/typeInference/"

  protected def doInjectorTest(injector: SyntheticMembersInjector): Unit = {
    val extensionPoint = Extensions.getRootArea.getExtensionPoint(SyntheticMembersInjector.EP_NAME)
    extensionPoint.registerExtension(injector)
    try {
      doTest()
    } finally {
      extensionPoint.unregisterExtension(injector)
    }
  }

  def configureFromFileText(fileName: String, fileText: Option[String]): ScalaFile = {
    val text = fileText.getOrElse {
      val filePath = folderPath + fileName
      val ioFile: File = new File(filePath)
      FileUtil.loadFile(ioFile, CharsetToolkit.UTF8)
    }
    configureFromFileTextAdapter(fileName, StringUtil.convertLineSeparators(text.trim))
    getFileAdapter.asInstanceOf[ScalaFile]
  }

  protected def addFileToProject(fileName: String, text: String): PsiFile = {
    inWriteAction {
      val vFile = LightPlatformTestCase.getSourceRoot.createChildData(null, fileName)
      VfsUtil.saveText(vFile, text)
      val psiFile = LightPlatformTestCase.getPsiManager.findFile(vFile)
      assertNotNull("Can't create PsiFile for '" + fileName + "'. Unknown file type most probably.", vFile)
      assertTrue(psiFile.isPhysical)
      vFile.setCharset(CharsetToolkit.UTF8_CHARSET)
      PsiDocumentManager.getInstance(getProjectAdapter).commitAllDocuments()
      psiFile
    }
  }

  protected def doTest(): Unit = doTest(None, getTestName(false) + ".scala")
}