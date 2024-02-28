package com.demo.util;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.nio.ByteBuffer;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.*;
import com.demo.service.TextractService;

/**
 * Hello world!
 *
 */
@Service
public class PDFUtils {
	@Autowired
	AmazonTextract textractClient ;
	
	

	public String cropPDFMM(float x, float y, float width, float height, String srcFilePath) throws IOException {
		
		// helper functions to convert between mm <-> units
		Function<Float, Float> mmToUnits = (Float a) -> a / 0.352778f;
		Function<Float, Float> unitsToMm = (Float a) -> a * 0.352778f;

		// convert mm to units
		float xUnits = mmToUnits.apply(x);
		float yUnits = mmToUnits.apply(y);
		float widthUnits = mmToUnits.apply(width);
		float heightUnits = mmToUnits.apply(height);

		// extract the doc's file name
		File srcFile = new File(srcFilePath);
		String fileName = srcFile.getName();
		int dotIndex = fileName.lastIndexOf('.');
		String fileNameWithoutExtension = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);

		// crop each page
		PDDocument doc = PDDocument.load(srcFile);
		int nrOfPages = doc.getNumberOfPages();
		PDRectangle newBox = new PDRectangle(xUnits, yUnits, widthUnits, heightUnits);
		for (int i = 0; i < nrOfPages; i++) {
			doc.getPage(i).setCropBox(newBox);
		}

		// save the result & append -cropped to the file name
		File outFile = new File(fileNameWithoutExtension + "-cropped.pdf");
		doc.save(outFile);
		doc.close();
		
		
		/*
		 * Path path = Path.of(outFile.getCanonicalPath().toString()); byte[] fileBytes
		 * = Files.readAllBytes(path);
		 * 
		 * 
		 * 
		 * DetectDocumentTextRequest request = new DetectDocumentTextRequest()
		 * .withDocument(new Document().withBytes(ByteBuffer.wrap(fileBytes)));
		 * DetectDocumentTextResult result = textractClient.detectDocumentText(request);
		 * Map<String,String> extractedFields =
		 * extractFieldsFromBlocks(result.getBlocks());
		 * 
		 * System.out.println("extractedFields " + extractedFields);
		 * System.out.println("The path is " + outFile.getCanonicalPath());
		 */
		
		return outFile.getCanonicalPath();
	}

	private Map<String, String> extractFieldsFromBlocks(List<Block> blocks) {
		Map<String, String> extractFields = new HashMap<>();
		
		String output = "";
		for (Block block : blocks) {
			
			if("LINE".equals(block.getBlockType())) {
				String parts = block.getText();
				parts = parts.replace("Vendor", " ");
				String partsNew = parts.replaceAll("\\R"," ");
				output = output+partsNew;
			}
		}
		extractFields.put("Vendor", output);	
		return extractFields;
	}

	public void removePages(String srcFilePath, Integer[] pageRanges) throws IOException {
		// a helper function to test if a page is within a range
		BiPredicate<Integer, Integer[]> pageInInterval = (Integer page, Integer[] allPages) -> {
			for (int j = 0; j < allPages.length; j += 2) {
				int startPage = allPages[j];
				int endPage = allPages[j + 1];
				if (page >= startPage - 1 && page < endPage) {
					return true;
				}
			}
			return false;
		};

		File srcFile = new File(srcFilePath);
		PDDocument pdfDocument = PDDocument.load(srcFile);
		PDDocument tmpDoc = new PDDocument();
		var nrOfPages = pdfDocument.getNumberOfPages();

		// test if a page is within a range
		// if not, append the page to a temp. doc.
		for (int i = 0; i < nrOfPages; i++) {
			if (pageInInterval.test(i, pageRanges)) {
				continue;
			}
			tmpDoc.addPage(pdfDocument.getPage(i));
		}

		// save the temporary doc.
		tmpDoc.save(new File(srcFilePath));
		tmpDoc.close();
		pdfDocument.close();
	}

	public void mergePages(String srcFilePath) throws IOException {
		// SOURCE:
		// https://stackoverflow.com/questions/12093408/pdfbox-merge-2-portrait-pages-onto-a-single-side-by-side-landscape-page
		File srcFile = new File(srcFilePath);
		PDDocument pdfDocument = PDDocument.load(srcFile);
		PDDocument outPdf = new PDDocument();

		for (int i = 0; i < pdfDocument.getNumberOfPages(); i += 2) {
			PDPage page1 = pdfDocument.getPage(i);
			PDPage page2 = pdfDocument.getPage(i + 1);

			PDRectangle pdf1Frame = page1.getCropBox();
			PDRectangle pdf2Frame = page2.getCropBox();
			PDRectangle outPdfFrame = new PDRectangle(pdf1Frame.getWidth() + pdf2Frame.getWidth(),
					Math.max(pdf1Frame.getHeight(), pdf2Frame.getHeight()));

			// Create output page with calculated frame and add it to the document
			COSDictionary dict = new COSDictionary();
			dict.setItem(COSName.TYPE, COSName.PAGE);
			dict.setItem(COSName.MEDIA_BOX, outPdfFrame);
			dict.setItem(COSName.CROP_BOX, outPdfFrame);
			dict.setItem(COSName.ART_BOX, outPdfFrame);
			PDPage newP = new PDPage(dict);
			outPdf.addPage(newP);

			// Source PDF pages has to be imported as form XObjects to be able to insert
			// them at a specific point in the output page
			LayerUtility layerUtility = new LayerUtility(outPdf);
			PDFormXObject formPdf1 = layerUtility.importPageAsForm(pdfDocument, page1);
			PDFormXObject formPdf2 = layerUtility.importPageAsForm(pdfDocument, page2);

			AffineTransform afLeft = new AffineTransform();
//            AffineTransform afLeft2 = AffineTransform.getTranslateInstance(85, -10);
			layerUtility.appendFormAsLayer(newP, formPdf1, afLeft, "left" + i);
			AffineTransform afRight = AffineTransform.getTranslateInstance(pdf1Frame.getWidth(), 0);
			layerUtility.appendFormAsLayer(newP, formPdf2, afRight, "right" + i);
		}

		outPdf.save(srcFile);
		outPdf.close();
		pdfDocument.close();
	}

	public void splitPDF(String srcFilePath, int nrOfPages) throws IOException {
		// extract file's name
		File srcFile = new File(srcFilePath);
		String fileName = srcFile.getName();
		int dotIndex = fileName.lastIndexOf('.');
		String fileNameWithoutExtension = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);

		PDDocument pdfDocument = PDDocument.load(srcFile);

		// extract every nrOfPages to a temporary document
		// append an index to its name and save it
		for (int i = 1; i < pdfDocument.getNumberOfPages(); i += nrOfPages) {
			Splitter splitter = new Splitter();

			int fromPage = i;
			int toPage = i + nrOfPages;
			splitter.setStartPage(fromPage);
			splitter.setEndPage(toPage);
			splitter.setSplitAtPage(toPage - fromPage);

			List<PDDocument> lst = splitter.split(pdfDocument);

			PDDocument pdfDocPartial = lst.get(0);
			File f = new File(fileNameWithoutExtension + "-" + i + ".pdf");
			pdfDocPartial.save(f);
			pdfDocPartial.close();
		}
		pdfDocument.close();
	}

	public static void cropPdfImage(String path) {
//        String srcFilePath = "/Users/user/projects/pdf_utils/file.pdf";
//   	String srcFilePath = "D:\\Downloads\\pdf_utils-main (2)\\pdf_utils-main\\pdf_utils-main\\singlepagepdf.pdf";
		String srcFilePath = path;
//        String srcFilePath = " C:/Users/HP/OneDrive/Desktop/Shiavnski Docs/pdf_utils-main/pdf_utils-main/SinglePagePDF.pdf";
		PDFUtils app = new PDFUtils();

		try {
			///// crop pdf
			



// singlepage			String resultFilePath1 = app.cropPDFMM(0f, 0f, 27f, 100f, srcFilePath);
			String resultFilePath1 = app.cropPDFMM(0f, 183f, 100f, 200f, srcFilePath);
			/*
			 * String resultFilePath2 = app.cropPDFMM(26.5f, 0f, 5.5f, 100f, srcFilePath);
			 * String resultFilePath3 = app.cropPDFMM(30f, 0f, 5.5f, 100f, srcFilePath);
			 * String resultFilePath4 = app.cropPDFMM(34.5f, 0f, 5.5f, 110f, srcFilePath);
			 * String resultFilePath5 = app.cropPDFMM(39.5f, 0f, 5.5f, 110f, srcFilePath);
			 * 
			 * String resultFilePath6 = app.cropPDFMM(44f, 0f, 5.5f, 110f, srcFilePath);
			 * String resultFilePath7 = app.cropPDFMM(48f, 0f, 5.5f, 110f, srcFilePath);
			 * 
			 * String resultFilePath8 = app.cropPDFMM(24f, 110f, 5.5f, 65f, srcFilePath);
			 * String resultFilePath9 = app.cropPDFMM(28f, 110f, 5.5f, 65f, srcFilePath);
			 * String resultFilePath10 = app.cropPDFMM(9f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath11= app.cropPDFMM(14.5f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath12= app.cropPDFMM(19f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath13= app.cropPDFMM(24f, 180f, 15f, 150f, srcFilePath);
			 * String resultFilePath14 = app.cropPDFMM(39f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath15 = app.cropPDFMM(43f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath16= app.cropPDFMM(48f, 180f, 5.5f, 150f, srcFilePath);
			 * String resultFilePath17= app.cropPDFMM(52f, 180f, 5.5f, 150f, srcFilePath);
			 * 
			 */
			///// remove pages
//            app.removePages(resultFilePath, new Integer[] {1, 18, 310, 322});

			///// split pages
//            app.splitPDF(resultFilePath, 20);

			System.out.println("Done!");
		} catch (Exception e) {
			System.out.println(e);
			System.err.println(e.getStackTrace());
			System.err.println(e.getCause());
		}
	}
}
