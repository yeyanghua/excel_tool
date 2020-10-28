package com.cmxy.excel.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.cmxy.excel.entity.Staff;
import com.cmxy.excel.utils.IDCardValidate;
import com.cmxy.excel.utils.MD5Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Controller
@Slf4j
public class MainController {

    @Autowired
    private RestTemplate restTemplate;

    @Value(value = "${tianyan.appid}")
    public String appId;

    @Value(value = "${tianyan.security}")
    public String appSecurity;

    private static final String URL = "https://api.shumaidata.com/v4/bankcard4/check";


    @RequestMapping(value = "/index")
    public String toIndex() {
        return "index";
    }

    /**
     * 实现文件上传
     */
    @RequestMapping("/file")
    @ResponseBody
    public String file(@RequestParam("fileName") MultipartFile file) throws IOException {
        InputStream inputStream = file.getInputStream();
        Workbook workbook = new XSSFWorkbook(inputStream);
        Sheet sheet = workbook.getSheetAt(0);
        List<Staff> staffList = new ArrayList<>();
        Map<String, String> errorMap = new HashMap<>();
        Set<String> set = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            sheet.getRow(i).getCell(1).setCellType(CellType.STRING);
            sheet.getRow(i).getCell(8).setCellType(CellType.STRING);
            sheet.getRow(i).getCell(21).setCellType(CellType.STRING);
            sheet.getRow(i).getCell(24).setCellType(CellType.STRING);

            String name = sheet.getRow(i).getCell(1).getStringCellValue();
            String idCard = sheet.getRow(i).getCell(8).getStringCellValue();
            String phone = sheet.getRow(i).getCell(21).getStringCellValue();
            String bankNo = sheet.getRow(i).getCell(24).getStringCellValue();
            Staff staff = Staff.builder()
                    .name(name)
                    .idCard(idCard)
                    .phone(phone)
                    .bankNo(bankNo)
                    .column(i + 1)
                    .build();
            staffList.add(staff);
        }
        List<Integer> duplicateIdCard = new ArrayList<>();
        List<Integer> duplicatePhone = new ArrayList();
        List<Integer> illegalIdCard = new ArrayList();
        List<Integer> emptyPhone = new ArrayList();

        boolean hasDuplicateIdCard = false;
        boolean hasDuplicatePhone = false;
        boolean hasIllegalIdCard = false;
        boolean hasEmptyPhone = false;

        Iterator<Staff> iterator = staffList.iterator();
        while (iterator.hasNext()) {
            Staff vo = iterator.next();
            //校验身份证是否重复
            boolean success = set.add(vo.getIdCard());
            if (!success) {
                duplicateIdCard.add(vo.getColumn());
                hasDuplicateIdCard = true;
            }
            //检验身份证是否合法
            boolean legal = IDCardValidate.isIDNumber(vo.getIdCard());
            if (!legal) {
                illegalIdCard.add(vo.getColumn());
                hasIllegalIdCard = true;
            }
            //检验手机号是否存在空
            if (StringUtils.isEmpty(vo.getPhone())) {
                emptyPhone.add(vo.getColumn());
                hasEmptyPhone = true;
            }
            //判断手机号是否重复
            success = set.add(vo.getPhone());
            if (!success && !StringUtils.isEmpty(vo.getPhone())) {
                duplicatePhone.add(vo.getColumn());
                hasDuplicatePhone = true;
            }

        }
        if (hasDuplicateIdCard) {
            errorMap.put("存在身份证重复的数据，所在行：", duplicateIdCard.toString());
        }
        if (hasIllegalIdCard) {
            errorMap.put("存在身份证错误的数据，所在行", illegalIdCard.toString());
        }
        if (hasEmptyPhone) {
            errorMap.put("存在手机号为空的数据，所在行", emptyPhone.toString());
        }
        if (hasDuplicatePhone) {
            errorMap.put("存在手机号重复的数据，所在行", duplicatePhone.toString());
        }
        //只要存在错误就删除对应的数据不判断银行卡是否正确（每次判断都要钱）
        if (!hasDuplicateIdCard && !hasIllegalIdCard && hasEmptyPhone && hasDuplicatePhone) {
            iterator.remove();
        }
        HttpEntity<String> formEntity = new HttpEntity<String>(null, null);
        //接口签名
        String timestamp = System.currentTimeMillis() + "";
        String sign = MD5Utils.encrypt(appId + "&" + timestamp + "&" + appSecurity);
        //判断银行卡是否正确
        List<Integer> emptyBankNo = new ArrayList<>();
        List<Integer> checkFailList = new ArrayList<>();
        List<Integer> mismatchList = new ArrayList<>();
        List<Integer> unauthorizedList = new ArrayList<>();
        List<Integer> cancelList = new ArrayList<>();
        staffList.forEach(vo -> {
            //银行卡为空的直接跳过
            if (StringUtils.isEmpty(vo.getBankNo())) {
                emptyBankNo.add(vo.getColumn());
                return;
            }
            Map<String, Object> maps = new HashMap<>();
            maps.put("appid", appId);
            maps.put("timestamp", timestamp);
            maps.put("sign", sign);
            maps.put("idcard", vo.getIdCard());
            maps.put("name", vo.getName());
            maps.put("bankcard", vo.getBankNo());
            maps.put("mobile", vo.getPhone());
            ResponseEntity<String> exchange = restTemplate.exchange(URL + "?appid={appid}&timestamp={timestamp}&sign={sign}&idcard={idcard}&name={name}&bankcard={bankcard}&mobile={mobile}",
                    HttpMethod.GET,
                    formEntity, String.class, maps);
            String body = exchange.getBody();
            if (exchange.getStatusCodeValue() != 200) {
                //校验失败
                checkFailList.add(vo.getColumn());
            } else {
                //匹配不上的情况
                JSONObject response = JSONObject.parseObject(body);
                JSONObject date = response.getJSONObject("data");
                Integer result = date.getInteger("result");
                switch (result) {
                    //不匹配
                    case 1:
                        mismatchList.add(vo.getColumn());
                        break;
                    case 2:
                        unauthorizedList.add(vo.getColumn());
                        break;
                    case 3:
                        cancelList.add(vo.getColumn());
                        break;
                    default:
                        break;
                }
            }


        });

        if (!emptyBankNo.isEmpty()) {
            errorMap.put("存在银行卡号为空的数据，所在行", emptyBankNo.toString());
        }

        if (!checkFailList.isEmpty()) {
            errorMap.put("存在银行卡校验失败的数据，所在行", emptyBankNo.toString());
        }

        if (!mismatchList.isEmpty()) {
            errorMap.put("存在银行卡号不匹配的数据，所在行", mismatchList.toString());
        }

        if (!unauthorizedList.isEmpty()) {
            errorMap.put("存在银行卡号未认证的数据，所在行", unauthorizedList.toString());
        }
        if (!cancelList.isEmpty()) {
            errorMap.put("存在银行卡号已注销的数据，所在行", cancelList.toString());
        }
        workbook.close();
        inputStream.close();
        if (errorMap.isEmpty()) {
            return "校验成功，不存在错误!";
        }
        return JSON.toJSONString(errorMap);
    }


}
