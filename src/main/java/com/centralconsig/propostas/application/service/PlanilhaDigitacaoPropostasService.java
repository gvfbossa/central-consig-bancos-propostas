package com.centralconsig.propostas.application.service;

import com.centralconsig.propostas.application.dto.PropostaPlanilhaResponseDTO;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlanilhaDigitacaoPropostasService {

    @Value("${planilha.digitacao.propostas}")
    private String urlPlanilha;

    private static final Logger log = LoggerFactory.getLogger(PlanilhaDigitacaoPropostasService.class);

    private Sheets sheetsService;

    private static final String RANGE = "'DIGITACAO ROBO'!A2:F";

    @PostConstruct
    public void init() throws Exception {
        InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream("google/centralconsig-crawler-sheets-54eb9933de47.json");

        GoogleCredentials credentials = GoogleCredentials.fromStream(is)
                .createScoped(List.of("https://www.googleapis.com/auth/spreadsheets"));

        sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("LeitorPlanilhaDigitacao")
                .build();
    }

    public List<PropostaPlanilhaResponseDTO> lerPropostasDaPlanilha() {
        try {
            String spreadsheetId = extrairSpreadsheetId(urlPlanilha);

            ValueRange response = sheetsService.spreadsheets().values()
                    .get(spreadsheetId, RANGE)
                    .execute();

            List<List<Object>> valores = response.getValues();

            List<PropostaPlanilhaResponseDTO> propostas = new ArrayList<>();

            if (valores != null) {
                for (List<Object> linha : valores) {
                    PropostaPlanilhaResponseDTO proposta = new PropostaPlanilhaResponseDTO();
                    proposta.setCpf(obterValor(linha, 0));
                    proposta.setNome(obterValor(linha, 1));
                    proposta.setMatricula(obterValor(linha, 2));
                    proposta.setOrgao(obterValor(linha, 3));
                    proposta.setCodigoBeneficio(obterValor(linha, 4));
                    proposta.setProposta(obterValor(linha, 5));
                    propostas.add(proposta);
                }
            }

            return propostas;

        } catch (Exception e) {
            log.error("Erro ao capturar dados da Planilha de Propostas. Erro: " + e.getMessage());
            return List.of();
        }
    }

    public void atualizarStatusProposta(String cpf, String status) throws IOException {
        String spreadsheetId = extrairSpreadsheetId(urlPlanilha);

        int linha = -1;
        List<PropostaPlanilhaResponseDTO> dados = lerPropostasDaPlanilha();
        for (int i = 0; i < dados.size(); i++) {
            if (dados.get(i).getCpf().equals(cpf)) {
                linha = i + 2;
                break;
            }
        }
        if (linha == -1)
            throw new IllegalArgumentException("CPF não encontrado na planilha: " + cpf);

        String range = "'DIGITACAO ROBO'!F" + linha;

        List<List<Object>> values = List.of(List.of(status));

        ValueRange body = new ValueRange().setValues(values);

        sheetsService.spreadsheets().values()
                .update(spreadsheetId, range, body)
                .setValueInputOption("RAW")
                .execute();
    }


    private String extrairSpreadsheetId(String url) {
        String[] partes = url.split("/d/");
        String id = partes[1].split("/")[0];
        return id;
    }

    private String obterValor(List<Object> linha, int index) {
        return index < linha.size() ? linha.get(index).toString() : "";
    }
}
