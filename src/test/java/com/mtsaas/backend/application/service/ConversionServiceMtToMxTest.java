package com.mtsaas.backend.application.service;

import com.mtsaas.backend.domain.Conversion;
import com.mtsaas.backend.domain.Role;
import com.mtsaas.backend.domain.User;
import com.mtsaas.backend.domain.swift.mt.MtParser;
import com.mtsaas.backend.domain.swift.mx.Camt053Generator;
import com.mtsaas.backend.domain.swift.mx.Mt102Generator;
import com.mtsaas.backend.domain.swift.mx.MxGenerator;
import com.mtsaas.backend.domain.swift.mx.MxParser;
import com.mtsaas.backend.domain.swift.mx.Pacs008Generator;
import com.mtsaas.backend.domain.swift.mx.Pacs009CovGenerator;
import com.mtsaas.backend.domain.swift.mx.Pacs009Generator;
import com.mtsaas.backend.dto.CreditBalanceResponse;
import com.mtsaas.backend.infrastructure.repository.ConversionRepository;
import com.mtsaas.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversionServiceMtToMxTest {

    private static final String SENDER_BIC = "BANKDEFFXXX";
    private static final String RECEIVER_BIC = "BANKBEBBXXX";
    private static final String UETR = "123e4567-e89b-42d3-a456-426614174000";

    @Mock
    private MxParser mxParser;

    @Mock
    private ConversionRepository conversionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CreditService creditService;

    private ConversionService service;

    @BeforeEach
    void setUp() {
        List<MxGenerator> mxGenerators = List.of(
                new Pacs008Generator(),
                new Pacs009Generator(),
                new Pacs009CovGenerator(),
                new Mt102Generator(),
                new Camt053Generator());
        service = new ConversionService(
                new MtParser(),
                mxParser,
                mxGenerators,
                List.of(),
                conversionRepository,
                userRepository,
                creditService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.25");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(conversionRepository.save(any(Conversion.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void convertsMt103CustomerPaymentToPacs008AndLogsAnonymousSuccess() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt103(), "MT103");

        assertTrue(xml.contains("<MsgDefIdr>pacs.008.001.08</MsgDefIdr>"));
        assertTrue(xml.contains("<BizMsgIdr>REF103ABC</BizMsgIdr>"));
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"USD\">1234.56</IntrBkSttlmAmt>"));
        assertTrue(xml.contains("<InstdAmt Ccy=\"EUR\">1000.50</InstdAmt>"));
        assertTrue(xml.contains("<ChrgBr>SHAR</ChrgBr>"));
        assertTrue(xml.contains("<UETR>" + UETR + "</UETR>"));
        assertTrue(xml.contains("<IBAN>DE89370400440532013000</IBAN>"));
        assertTrue(xml.contains("<Nm>John Doe</Nm>"));
        assertTrue(xml.contains("<Nm>Jane Beneficiary</Nm>"));
        assertTrue(xml.contains("<Ustrd>INVOICE 987</Ustrd>"));

        Conversion saved = captureLastSavedConversion();
        assertEquals("MT_TO_MX", saved.getConversionType());
        assertEquals(Conversion.Status.SUCCESS, saved.getStatus());
        assertEquals("203.0.113.25", saved.getIpAddress());
        assertTrue(saved.getOutputContent().contains("pacs.008.001.08"));
        verify(creditService, never()).recordCreditUsage(any(), any(), any(), any(), any());
    }

    @Test
    void convertsMt103WithSessionAndSequenceInBlock1() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt103WithSessionAndSequence(), null);

        assertTrue(xml.contains("<MsgDefIdr>pacs.008.001.08</MsgDefIdr>"));
        assertTrue(xml.contains("<BizMsgIdr>TRANSREF123</BizMsgIdr>"));
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"EUR\">125000.50</IntrBkSttlmAmt>"));
        assertTrue(xml.contains("<Nm>JOHN DOE</Nm>"));
        assertTrue(xml.contains("<Nm>JANE SMITH</Nm>"));
    }

    @Test
    void convertsMt202FinancialInstitutionTransferToPacs009() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt202(), null);

        assertTrue(xml.contains("<MsgDefIdr>pacs.009.001.08</MsgDefIdr>"));
        assertTrue(xml.contains("<FICdtTrf>"));
        assertTrue(xml.contains("<InstrId>REF202ABC</InstrId>"));
        assertTrue(xml.contains("<EndToEndId>RELREF202</EndToEndId>"));
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"EUR\">98765.43</IntrBkSttlmAmt>"));
        assertTrue(xml.contains("<Cdtr>"));
        assertTrue(xml.contains("<BICFI>CRDTDEFFXXX</BICFI>"));
    }

    @Test
    void convertsMt202CovToPacs009WithUnderlyingCustomerTransfer() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt202Cov(), null);

        assertTrue(xml.contains("<MsgDefIdr>pacs.009.001.08</MsgDefIdr>"));
        assertTrue(xml.contains("<UndrlygCstmrCdtTrf>"));
        assertTrue(xml.contains("<Nm>Underlying Debtor</Nm>"));
        assertTrue(xml.contains("<Nm>Underlying Creditor</Nm>"));
        assertTrue(xml.contains("<InstdAmt Ccy=\"GBP\">2500.00</InstdAmt>"));
    }

    @Test
    void convertsMt102BulkPaymentToPacs008WithBulkTransactionCount() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt102(), null);

        assertTrue(xml.contains("<MsgDefIdr>pacs.008.001.08</MsgDefIdr>"));
        assertTrue(xml.contains("<NbOfTxs>1</NbOfTxs>"));
        assertTrue(xml.contains("<InstrId>BULKTXN1</InstrId>"));
        assertTrue(xml.contains("<IntrBkSttlmAmt Ccy=\"USD\">42.25</IntrBkSttlmAmt>"));
        assertTrue(xml.contains("<Nm>Bulk Beneficiary</Nm>"));
    }

    @Test
    void convertsMt940StatementToCamt053() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        String xml = service.convertMtToMx(mt940(), null);

        assertTrue(xml.contains("urn:iso:std:iso:20022:tech:xsd:camt.053.001.08"));
        assertTrue(xml.contains("<BkToCstmrStmt>"));
        assertTrue(xml.contains("<Id>00001</Id>"));
        assertTrue(xml.contains("<ElctrncSeqNb>001</ElctrncSeqNb>"));
        assertTrue(xml.contains("<Amt Ccy=\"USD\">1000.00</Amt>"));
        assertTrue(xml.contains("<Amt Ccy=\"USD\">1500.50</Amt>"));
        assertTrue(xml.contains("<Ustrd>PAYMENT DETAILS</Ustrd>"));
    }

    @Test
    void rejectsAnonymousConversionWhenGuestLimitReachedBeforeParsing() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25"))
                .thenReturn((long) TrialLimitPolicy.GUEST_FREE_CONVERSIONS);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.convertMtToMx(mt103(), "MT103"));

        assertEquals("ANONYMOUS_LIMIT_REACHED", error.getMessage());
        Conversion saved = captureLastSavedConversion();
        assertEquals(Conversion.Status.FAILED, saved.getStatus());
        assertEquals("ANONYMOUS_LIMIT_REACHED", saved.getErrorMessage());
    }

    @Test
    void deductsOneCreditForAuthenticatedSuccessfulConversion() {
        User user = User.builder()
                .email("tester@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .credits(3L)
                .emailVerified(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester@example.com", "password", "ROLE_USER"));
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(user));
        when(creditService.getUserCreditBalance("tester@example.com"))
                .thenReturn(CreditBalanceResponse.builder().availableCredits(3L).build());

        String xml = service.convertMtToMx(mt103(), "MT103");

        assertTrue(xml.contains("pacs.008.001.08"));
        verify(creditService).recordCreditUsage(
                eq(user),
                eq(1L),
                eq("MT_TO_MX"),
                eq("Converted MT message of type 103"),
                any(Conversion.class));
    }

    @Test
    void rejectsAuthenticatedConversionWhenCreditsAreExhausted() {
        User user = User.builder()
                .email("tester@example.com")
                .passwordHash("hash")
                .role(Role.USER)
                .credits(0L)
                .emailVerified(true)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester@example.com", "password", "ROLE_USER"));
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(user));
        when(creditService.getUserCreditBalance("tester@example.com"))
                .thenReturn(CreditBalanceResponse.builder().availableCredits(0L).build());

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.convertMtToMx(mt103(), "MT103"));

        assertEquals("INSUFFICIENT_CREDITS", error.getMessage());
        verify(creditService, never()).recordCreditUsage(any(), any(), any(), any(), any());
        assertEquals(Conversion.Status.FAILED, captureLastSavedConversion().getStatus());
    }

    @Test
    void rejectsUnsupportedMtTypeAndPersistsFailure() {
        when(conversionRepository.countByIpAddressAndUserIsNull("203.0.113.25")).thenReturn(0L);

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.convertMtToMx(mt999(), null));

        assertEquals("Unsupported MT type: 999", error.getMessage());
        Conversion saved = captureLastSavedConversion();
        assertEquals(Conversion.Status.FAILED, saved.getStatus());
        assertEquals("Unsupported MT type: 999", saved.getErrorMessage());
        assertFalse(saved.getInputContent().isBlank());
    }

    private Conversion captureLastSavedConversion() {
        ArgumentCaptor<Conversion> captor = ArgumentCaptor.forClass(Conversion.class);
        verify(conversionRepository).save(captor.capture());
        return captor.getValue();
    }

    private String mt103() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O1030000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{3:{121:" + UETR + "}}{4:\n"
                + ":20:REF103ABC\n"
                + ":23B:CRED\n"
                + ":32A:260722USD1234,56\n"
                + ":33B:EUR1000,50\n"
                + ":50K:/DE89370400440532013000\n"
                + "John Doe\n"
                + "Main Street 1\n"
                + "DE Berlin\n"
                + ":57A:DEUTDEFFXXX\n"
                + ":59:/GB29NWBK60161331926819\n"
                + "Jane Beneficiary\n"
                + "Queen Street 2\n"
                + "GB London\n"
                + ":70:INVOICE 987\n"
                + ":71A:SHA\n"
                + "-}";
    }

    private String mt202() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O2020000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{3:{121:" + UETR + "}}{4:\n"
                + ":20:REF202ABC\n"
                + ":21:RELREF202\n"
                + ":32A:260722EUR98765,43\n"
                + ":52A:DBTRDEFFXXX\n"
                + ":57A:INTMDEFFXXX\n"
                + ":58A:CRDTDEFFXXX\n"
                + "-}";
    }

    private String mt103WithSessionAndSequence() {
        return "{1:F01BANKDEFFAXXX0000000000}{2:I103BANKBEBBAXXXN}{4:\n"
                + ":20:TRANSREF123\n"
                + ":23B:CRED\n"
                + ":32A:260518EUR125000,50\n"
                + ":50K:/12345678\n"
                + "JOHN DOE\n"
                + "123 MAPLE STREET\n"
                + "LONDON\n"
                + ":59:/87654321\n"
                + "JANE SMITH\n"
                + "456 OAK AVENUE\n"
                + "BRUSSELS\n"
                + ":71A:SHA\n"
                + "-}";
    }

    private String mt202Cov() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O2020000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{3:{119:COV}{121:" + UETR + "}}{4:\n"
                + ":20:REFCOVABC\n"
                + ":21:RELREFCOV\n"
                + ":32A:260722USD2500,00\n"
                + ":52A:DBTRDEFFXXX\n"
                + ":58A:CRDTDEFFXXX\n"
                + ":50K:/123456789\n"
                + "Underlying Debtor\n"
                + "DE Frankfurt\n"
                + ":59:/987654321\n"
                + "Underlying Creditor\n"
                + "GB London\n"
                + ":33B:GBP2500,00\n"
                + ":70:COVER PAYMENT\n"
                + "-}";
    }

    private String mt102() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O1020000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{3:{121:" + UETR + "}}{4:\n"
                + ":20:BULKREF1\n"
                + ":21:BULKTXN1\n"
                + ":32B:USD42,25\n"
                + ":50K:/123456789\n"
                + "Bulk Debtor\n"
                + "US New York\n"
                + ":59:/987654321\n"
                + "Bulk Beneficiary\n"
                + "US Boston\n"
                + ":70:PAYROLL\n"
                + "-}";
    }

    private String mt940() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O9400000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{4:\n"
                + ":20:STMREF1\n"
                + ":25:BANKDEFF/123456789\n"
                + ":28C:00001/001\n"
                + ":60F:C260721USD1000,00\n"
                + ":61:2607220722C500,50NTRFNONREF\n"
                + ":86:PAYMENT DETAILS\n"
                + ":62F:C260722USD1500,50\n"
                + "-}";
    }

    private String mt999() {
        return "{1:F01" + block1Bic(SENDER_BIC) + "}{2:O9990000000000" + block2Bic(RECEIVER_BIC) + "0000000000000000}{4:\n"
                + ":20:UNSUPPORTED\n"
                + "-}";
    }

    private String block1Bic(String bic11) {
        return bic11.substring(0, 8) + "X" + bic11.substring(8);
    }

    private String block2Bic(String bic11) {
        return bic11.substring(0, 8) + "X" + bic11.substring(8);
    }
}
