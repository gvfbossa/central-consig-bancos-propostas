package com.centralconsig.propostas.application.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PropostaPlanilhaResponseDTO {

    private String cpf;
    private String nome;
    private String matricula;
    private String orgao;
    private String codigoBeneficio;
    private String proposta;

}
