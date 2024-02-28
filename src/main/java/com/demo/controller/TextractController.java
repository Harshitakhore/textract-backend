package com.demo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.demo.service.TextractService;
import com.demo.vo.ExtractedFieldsDataVo;

@RestController
public class TextractController {
	@Autowired
	private TextractService textractService;

	@PostMapping("/extract-fields")
	public ResponseEntity<ExtractedFieldsDataVo> extractFieldsFromPdf(@RequestParam("file") MultipartFile file)
			throws Exception {
		try {
			ExtractedFieldsDataVo extractedFields = textractService.extractFieldsFromPdf(file);
			return ResponseEntity.ok(extractedFields);
		} catch (RuntimeException e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
		}
	}
}