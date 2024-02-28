package com.demo.vo;

import java.util.List;
import java.util.Map;

import lombok.Data;

@Data
public class ExtractedFieldsDataVo {
	
	Map<String, String> head;
	List<List<Map<String, String>>> analyzePdf;

}
