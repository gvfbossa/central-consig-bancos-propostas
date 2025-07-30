package com.centralconsig.propostas.application.service.crawler;


import com.centralconsig.core.application.dto.response.SystemConfigurationResponseDTO;
import com.centralconsig.core.application.service.ClienteService;
import com.centralconsig.core.application.service.PropostaService;
import com.centralconsig.core.application.service.crawler.QueroMaisCreditoLoginService;
import com.centralconsig.core.application.service.crawler.UsuarioLoginQueroMaisCreditoService;
import com.centralconsig.core.application.service.crawler.WebDriverService;
import com.centralconsig.core.application.service.system.SystemConfigurationService;
import com.centralconsig.core.application.utils.CrawlerUtils;
import com.centralconsig.core.domain.entity.*;
import com.centralconsig.propostas.application.dto.PropostaPlanilhaResponseDTO;
import com.centralconsig.propostas.application.service.PlanilhaDigitacaoPropostasService;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class QueroMaisCreditoPropostaService {

    private final WebDriverService webDriverService;

    private final ClienteService clienteService;
    private final PropostaService propostaService;

    private final QueroMaisCreditoLoginService queroMaisCreditoLoginService;
    private final UsuarioLoginQueroMaisCreditoService usuarioLoginQueroMaisCreditoService;
    private final SystemConfigurationService systemConfigurationService;
    private final PlanilhaDigitacaoPropostasService planilhaDigitacaoPropostasService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    List<PropostaPlanilhaResponseDTO> dadosPlanilha = null;

    private SystemConfigurationResponseDTO systemConfiguration;

    private static final Logger log = LoggerFactory.getLogger(QueroMaisCreditoPropostaService.class);

    public QueroMaisCreditoPropostaService(WebDriverService webDriverService, ClienteService clienteService,
               PropostaService propostaService, UsuarioLoginQueroMaisCreditoService usuarioLoginQueroMaisCreditoService,
               QueroMaisCreditoLoginService queroMaisCreditoLoginService, SystemConfigurationService systemConfigurationService,
               PlanilhaDigitacaoPropostasService planilhaDigitacaoPropostasService) {
        this.webDriverService = webDriverService;
        this.clienteService = clienteService;
        this.propostaService = propostaService;
        this.usuarioLoginQueroMaisCreditoService = usuarioLoginQueroMaisCreditoService;
        this.queroMaisCreditoLoginService = queroMaisCreditoLoginService;
        this.systemConfigurationService = systemConfigurationService;
        this.planilhaDigitacaoPropostasService = planilhaDigitacaoPropostasService;
    }


//    @Scheduled(cron = "0 0,10,20,30 7-22 * * *", zone = "America/Sao_Paulo")
    //@Scheduled(fixedDelay = 1000)
    public void cadastrarPropostasAuto() {
        systemConfiguration = systemConfigurationService.getSystemConfigurations();

        if (!systemConfiguration.isPropostaAutomatica()) {
            log.info("Propostas automáticas desativadas.");
            return;
        }
        if (systemConfiguration.isPropostaAutomaticaPlanilha()) {
            log.info("Propostas Automáticas de Planilha estão ativas, elas tem prioridade, portanto abortando este processo...");
            return;
        }
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Criar Proposta já em execução. Ignorando nova tentativa.");
            return;
        }
        try {
            log.info("Executar Propostas iniciado");
            criarProposta(false, null);
            log.info("Executar Propostas finalizado");
        } catch (Exception e) {
            log.error("Erro crítico ao criar propostas. Erro: " + e.getMessage());
        } finally {
         isRunning.set(false);
         CrawlerUtils.killChromeDrivers();
        }
    }

    @Scheduled(cron = "0 0,10,20,30 7-22 * * *", zone = "America/Sao_Paulo")
//    @Scheduled(fixedDelay = 1000)
    public void cadastrarPropostasPlanilhaAuto() {
        systemConfiguration = systemConfigurationService.getSystemConfigurations();

        if (!systemConfiguration.isPropostaAutomaticaPlanilha()) {
            log.info("Propostas automáticas de planilha desativadas.");
            return;
        }
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Criar Proposta Planilha já em execução. Ignorando nova tentativa.");
            return;
        }
        try {
            log.info("Executar Propostas Planilha iniciado");
            criarPropostaPlanilha();
            log.info("Executar Propostas Planilha finalizado");
        } catch (Exception e) {
            log.error("Erro crítico ao criar propostas. Erro: " + e.getMessage());
        } finally {
            isRunning.set(false);
            CrawlerUtils.killChromeDrivers();
        }
    }

    private void criarPropostaPlanilha() {
        if (dadosPlanilha != null)
            dadosPlanilha = null;
        dadosPlanilha = planilhaDigitacaoPropostasService.lerPropostasDaPlanilha()
                .stream().filter(c -> c.getProposta().isEmpty()).toList();

        if (dadosPlanilha.isEmpty()) {
            log.warn("A planilha está vazia ou não foram encontrados dados para processar.");
            return;
        }

        List<Cliente> clientesPlanilha = new ArrayList<>();

        for (PropostaPlanilhaResponseDTO clienteProposta : dadosPlanilha) {
            Cliente cliente = clienteService.findByCpf(clienteProposta.getCpf());
            if (cliente == null) {
                log.error("Cliente '" + clienteProposta.getCpf() + "' não encontrado.");
                continue;
            }
            else if (cliente.getDadosBancarios() == null || cliente.getDadosBancarios().getAgencia() == null) {
                log.error("Dados Bancários do Cliente '" + cliente.getCpf() + "' não encontrados...");
                continue;
            }
            clientesPlanilha.add(cliente);
        }
        if (clientesPlanilha.isEmpty()) {
            log.warn("Não há clientes a serem processados na planilha.");
            return;
        }
        criarProposta(true, clientesPlanilha);
    }

    @Scheduled(cron = "0 40,50 7-23 * * *", zone = "America/Sao_Paulo")
    //@Scheduled(fixedDelay = 1000)
    public void capturarDadosPropostasAuto() {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Capturar Dados Proposta já em execução. Ignorando nova tentativa.");
            return;
        }
        try {
            log.info("Capturar Dados Propostas iniciado");
            processarDadosProposta();
            log.info("Capturar Dados Propostas finalizado");
        } catch (Exception e) {
            log.error("Erro crítico ao Capturar Dados Propostas. Erro: " + e.getMessage());
            processarDadosProposta();
        } finally {
            isRunning.set(false);
            CrawlerUtils.killChromeDrivers();
        }
    }

    private void criarProposta(boolean isPlanilha, List<Cliente> clientesPlanilha) {
        List<UsuarioLoginQueroMaisCredito> usuarios = usuarioLoginQueroMaisCreditoService.retornaUsuariosParaCrawler().stream()
                .filter(usuario -> !usuario.isSomenteConsulta())
                .toList();

        List<Cliente> clientes;

        if (!isPlanilha)
            clientes = clienteService.clientesFiltradosPorMargem().stream()
                .filter(cliente -> cliente.getDadosBancarios() != null)
                .filter(cliente -> !cliente.getDadosBancarios().getBanco().isEmpty())
                .toList();
        else clientes = clientesPlanilha;

        log.info("Número elegível de clientes para preencher propostas: " + clientes.size());

        int totalClientes = clientes.size();
        int totalUsuarios = usuarios.size();
        int numThreads = Math.min(totalClientes, totalUsuarios);

        int clientesPorThread = (int) Math.ceil((double) totalClientes / numThreads);

        LocalDateTime tempoFinal = LocalDateTime.now().plusMinutes(9);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        try {
            for (int i = 0; i < numThreads; i++) {
                int start = i * clientesPorThread;
                int end = Math.min(start + clientesPorThread, totalClientes);

                if (start >= end) {
                    log.info("Thread {} não possui clientes para processar.", i);
                    continue;
                }

                List<Cliente> subLista = clientes.subList(start, end);
                UsuarioLoginQueroMaisCredito usuario = usuarios.get(i);

                executor.submit(() -> {
                    WebDriver driver = null;
                    try {
                        driver = webDriverService.criarDriver();


                        UsuarioLoginQueroMaisCredito usuario2 = new UsuarioLoginQueroMaisCredito();
                        usuario2.setUsername("45236717884_900411");
                        usuario2.setPassword("Sucesso@2025");

                        WebDriverWait wait = webDriverService.criarWait(driver);
                        if (!queroMaisCreditoLoginService.seleniumLogin(driver, usuario2)) {
                            log.error("Erro ao processar propostas. Falha no login.");
                            return;
                        }
                        for (Cliente cliente : subLista) {
                            if (LocalDateTime.now().isAfter(tempoFinal))
                                break;

                            try {
                                if (cliente.getPropostas().stream().anyMatch(p -> p.getDataCadastro().equals(LocalDate.now())))
                                    continue;

                                Proposta proposta = new Proposta();
                                proposta.setUsuario(usuario.getUsername());
                                processarCliente(driver, wait, cliente, proposta);
                                if (isPlanilha) {
                                    preencherNumeroPropostaNaPlanilha(cliente, proposta.getNumeroProposta());
                                }
                                voltarParaTelaInicial(driver, usuario);
                            } catch (Exception e) {
                                log.error("Erro ao processar cliente {}: {}", cliente.getCpf(), e.getMessage().substring(0, e.getMessage().indexOf("\n")));
                                voltarParaTelaInicial(driver, usuario);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Erro ao iniciar WebDriver para usuário {}: {}", usuario.getUsername(), e.getMessage());
                    } finally {
                        if (driver != null) {
                            webDriverService.fecharDriver(driver);
                        }
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(9, TimeUnit.MINUTES)) {
                log.warn("Timeout atingido. Forçando encerramento.");
                executor.shutdownNow();
            }

        } catch (Exception e) {
            log.error("Erro geral na criação de propostas: ", e);
        }
    }

    private void preencherNumeroPropostaNaPlanilha(Cliente cliente, String numeroProposta) {
        try {
            planilhaDigitacaoPropostasService.atualizarStatusProposta(cliente.getCpf(), numeroProposta);
            log.info("Planilha atualizada com OK para cliente: " + cliente.getCpf());
        } catch (Exception e) {
            log.error("Erro ao atualizar planilha para cliente " + cliente.getCpf() + ": " + e.getMessage());
        }
    }


    private void voltarParaTelaInicial(WebDriver driver, UsuarioLoginQueroMaisCredito usuario) {
        queroMaisCreditoLoginService.seleniumLogin(driver, usuario);
    }

    private void processarDadosProposta() {
        List<UsuarioLoginQueroMaisCredito> usuarios = usuarioLoginQueroMaisCreditoService.retornaUsuariosParaCrawler().stream()
                .filter(usuario -> !usuario.isSomenteConsulta()).toList();

        List<Proposta> propostas = propostaService.retornaPropostasPorFaltaDeInformacao();
        propostas.sort(Comparator.comparing(Proposta::getDataCadastro).reversed());

        if (propostas.isEmpty() || propostas.stream().noneMatch(p -> p.getDataCadastro().isEqual(LocalDate.now()))) {
            return;
        }

        int numThreads = usuarios.size();

        LocalDateTime tempoFinal = LocalDateTime.now().plusMinutes(9);

        try (ExecutorService executor = Executors.newFixedThreadPool(numThreads)) {
            for (int i = 0; i < numThreads; i++) {
                UsuarioLoginQueroMaisCredito usuario = usuarios.get(i % usuarios.size());

                executor.submit(() -> {
                    WebDriver driver = null;
                    WebDriverWait wait = null;
                    try {
                        driver = webDriverService.criarDriver();
                        wait = webDriverService.criarWait(driver);
                        queroMaisCreditoLoginService.seleniumLogin(driver, usuario);

                        for (Proposta proposta : propostas) {
                            try {
                                if (LocalDateTime.now().isAfter(tempoFinal)) {
                                    webDriverService.fecharDriver(driver);
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                                if (Thread.currentThread().isInterrupted())
                                    break;
                                if (!proposta.getUsuario().equals(usuario.getUsername()))
                                    continue;

                                buscarProposta(driver, wait, proposta.getNumeroProposta());
                                obtemInformacoesValoresProposta(driver, proposta);
                                processaPropostaECapturaLink(driver, wait, proposta);
                            } catch (Exception e) {
                                webDriverService.fecharDriver(driver);
                                driver = webDriverService.criarDriver();
                                wait = webDriverService.criarWait(driver);
                                queroMaisCreditoLoginService.seleniumLogin(driver, usuario);
                                acessarTelaEsteira(driver, wait);
                            }
                        }
                    } catch (Exception e) {
                        log.error("Erro ao processar proposta: " + e.getMessage().substring(0, e.getMessage().indexOf("\n")));
                    } finally {
                        webDriverService.fecharDriver(driver);
                    }
                });
            }
        } catch (Exception e) {
        Thread.currentThread().interrupt();
        }
    }

    private void processarCliente(WebDriver driver, WebDriverWait wait, Cliente cliente, Proposta proposta) {
        for (Vinculo vinculo : cliente.getVinculos()) {
            if (vinculo.getOrgao() == null)
                continue;

            Optional<HistoricoConsulta> historicoMaisRecente = vinculo.getHistoricos().stream()
                    .max(Comparator.comparing(HistoricoConsulta::getDataConsulta));
            if (historicoMaisRecente.isEmpty())
                continue;

            tentarExecutar(() -> {
                acessarTelaProposta(driver, wait);
                selecionaEmpregador(wait);
                preencheCpfCliente(driver, wait, cliente);
                selecionaClientePorMatricula(driver, wait, vinculo.getMatriculaPensionista(), cliente);
                preencheMargemCliente(driver, wait, historicoMaisRecente.get().getMargemBeneficio());
                calcularSimulacaoCliente(driver, wait);
                selecionarPrimeiraOpcaoCheckBoxTabela(driver, wait);
                isentarSeguroProposta(driver, wait);
                preencherDadosBancariosCliente(driver, wait, cliente);
                gravarProposta(driver, wait, cliente, proposta);
                adicionarPdfNaProposta(driver, wait);
                aprovarProposta(driver, wait);
            }, driver, wait);
        }
    }

    private void preencherDadosBancariosCliente(WebDriver driver, WebDriverWait wait, Cliente cliente) {
        WebElement banco = driver.findElement(By.id("ctl00_Cph_UcPrp_FIJN1_JnClientes_UcDadosClienteSnt_FIJN1_FIJanelaPanel2_UcDadosPagamento_UcDadosBancarios_txtBanco_CAMPO"));
        banco.click();
        CrawlerUtils.esperar(1);
        banco.sendKeys(cliente.getDadosBancarios().getBanco());

        CrawlerUtils.esperar(1);
        Actions actions = new Actions(driver);
        actions
                .sendKeys(Keys.TAB)
                .perform();

        CrawlerUtils.esperar(2);

        for (char c : cliente.getDadosBancarios().getAgencia().toCharArray()) {
            actions
                    .sendKeys(String.valueOf(c))
                    .pause(200)
                    .perform();
        }

        CrawlerUtils.esperar(2);

        actions
                .sendKeys(Keys.TAB)
                .perform();

        CrawlerUtils.esperar(2);

        actions
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .sendKeys(Keys.TAB)
                .perform();

        CrawlerUtils.esperar(2);

        for (char c : cliente.getDadosBancarios().getConta().toCharArray()) {
            actions
                    .sendKeys(String.valueOf(c))
                    .pause(100)
                    .perform();
        }

        CrawlerUtils.esperar(1);

        actions
                .sendKeys(Keys.TAB)
                .perform();

        CrawlerUtils.esperar(1);

        for (char c : cliente.getDadosBancarios().getDigitoConta().toCharArray()) {
            actions
                    .sendKeys(String.valueOf(c))
                    .pause(100)
                    .perform();
        }

        actions
                .pause(500)
                .sendKeys(Keys.TAB)
                .perform();
    }

    private void aguardar(long segundos, int count) {
        try {
            Thread.sleep(segundos* 1000L);
        } catch (Exception ignored) {}
        log.info("contando: " + count);
    }

    private boolean tentarExecutar(Runnable operacao,WebDriver driver, WebDriverWait wait) {
        for (int tentativa = 1; tentativa <= 3; tentativa++) {
            try {
                operacao.run();
                return true;
            } catch (Exception e) {
                if (tentativa < 3) {
                    driver.navigate().back();
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
        return false;
    }

    private boolean acessarTelaProposta(WebDriver driver, WebDriverWait wait) {
        WebElement menuCadastro = wait.until(ExpectedConditions.presenceOfElementLocated(
        By.xpath("//a[contains(text(),'Cadastro')]")));

        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", menuCadastro);

        WebElement opcaoProposta = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//a[contains(text(),'Proposta Cartão - SIAPE')]")));

        opcaoProposta.click();
        try {
            Thread.sleep(5000);
        } catch (Exception ignored) {}
        return true;
    }

    private boolean selecionaEmpregador(WebDriverWait wait) {
            WebElement selectElement = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.id("ctl00_Cph_UcPrp_FIJN1_JnDadosIniciais_UcDIni_cboOrigem4_CAMPO")));

            Select select = new Select(selectElement);
            select.selectByValue("202329");
        try {
            Thread.sleep(500);
        } catch (Exception ignored) {}
        return true;
    }

    private void preencheCpfCliente(WebDriver driver, WebDriverWait wait, Cliente cliente) {
        try {
            Thread.sleep(1000);
        } catch (Exception ignored) {}
        CrawlerUtils.preencherCpf(cliente.getCpf(), "ctl00_Cph_UcPrp_FIJN1_JnDadosIniciais_UcDIni_txtCPF_CAMPO", driver, wait);
        try {
          Thread.sleep(1000);
        } catch (Exception ignored) {}
        Actions actions = new Actions(driver);
        actions.sendKeys(Keys.ENTER).perform();
    }

    private void selecionaClientePorMatricula(WebDriver driver, WebDriverWait wait, String matricula, Cliente cliente) {
        try {
            wait.until(ExpectedConditions.frameToBeAvailableAndSwitchToIt("ctl00_Cph_UcPrp_FIJN1_JnDadosIniciais_UcDIni_popCliente_frameAjuda"));

            WebElement linkCliente = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//td[contains(text(), '" + matricula + "')]/preceding-sibling::td/a")
            ));

            linkCliente.click();

            Thread.sleep(2000);

            CrawlerUtils.interagirComAlert(driver);

            driver.switchTo().defaultContent();

            capturaTelefoneCliente(driver, cliente);
        } catch (Exception e) {
            webDriverService.fecharDriver(driver);
            driver = null;
        }
    }

    private void preencheMargemCliente(WebDriver driver, WebDriverWait wait, String margem) {
       JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("if (document.activeElement) document.activeElement.blur();");

        WebElement inputVlrMargem = driver.findElement(By.xpath("//input[contains(@id, 'txtVlrParcela_CAMPO')]"));

        Actions actions = new Actions(driver);
        actions.moveToElement(inputVlrMargem).click().sendKeys(margem).perform();

        js.executeScript("if (document.activeElement) document.activeElement.blur();");
    }

    private void calcularSimulacaoCliente(WebDriver driver, WebDriverWait wait) {
        WebElement btnCalcular = wait.until(ExpectedConditions.elementToBeClickable(
                By.id("ctl00_Cph_UcPrp_FIJN1_JnSimulacao_UcSimulacaoSnt_FIJanela1_FIJanelaPanel1_UcBtnCalc_btnCalcular_dvBtn")
        ));
        Actions actions = new Actions(driver);
        actions.moveToElement(btnCalcular).click().perform();

        int timer = 0;
        while (true) {
            if (!driver.getPageSource().contains("Não existem dados para exibição"))
                break;
            if (timer >= 30)
                break;

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            timer++;
        }
    }

    private void selecionarPrimeiraOpcaoCheckBoxTabela(WebDriver driver, WebDriverWait wait) {
        WebElement primeiraOpcao = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//table[contains(., 'Tabela') and contains(., 'Descrição Tabela')]//input[@type='checkbox']")));
        primeiraOpcao.click();
        try {
            Thread.sleep(2000);
        } catch (Exception ignored){}
    }

    private void isentarSeguroProposta(WebDriver driver, WebDriverWait wait) {
        WebElement itemSeguro = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//*[contains(text(), 'SEGURO ACIDENTE PESSOAL')]")));
        itemSeguro.click();

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {}

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.xpath("//*[contains(text(), 'SEGURO: Escolha o tipo de Proposta para o cliente')]")
        ));

        WebElement isentarRadio = driver.findElement(
                By.xpath("//label[contains(normalize-space(), 'Isentar o cliente')]")
        );
        isentarRadio.click();

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("cap_Prosseguir_Seguro_Confirma();");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {}
    }

    private void capturaTelefoneCliente(WebDriver driver, Cliente cliente) {
        WebElement dddInput = driver.findElement(By.id("ctl00_Cph_UcPrp_FIJN1_JnDadosCliente_UcDadosPessoaisClienteSnt_FIJN1_JnC_txtDddTelCelular_CAMPO"));

        WebElement celularInput = driver.findElement(By.id("ctl00_Cph_UcPrp_FIJN1_JnDadosCliente_UcDadosPessoaisClienteSnt_FIJN1_JnC_txtTelCelular_CAMPO"));

        String ddd = dddInput.getAttribute("value");
        String celular = celularInput.getAttribute("value");


        if (cliente.getTelefone() == null || cliente.getTelefone().isBlank() || !cliente.getTelefone().equals("(" + ddd + ") " + celular))
            cliente.setTelefone("(" + ddd + ") " + celular);
    }

    private void gravarProposta(WebDriver driver, WebDriverWait wait, Cliente cliente, Proposta proposta) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("if (document.activeElement) document.activeElement.blur();");

        WebElement botaoGravar = wait.until(ExpectedConditions.elementToBeClickable(By.id("ctl00_Cph_UcPrp_FIJN1_JnBotoes_UcBotoes_btnGravar_dvBtn")));

        Actions actions = new Actions(driver);
        actions.moveToElement(botaoGravar).click().perform();

        try {
            Thread.sleep(6000);
        } catch (InterruptedException ignored) {
        }

        CrawlerUtils.interagirComAlert(driver);

        WebElement msgSpan = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("ctl00_Cph_UcConf_lblMsg")));

        setaDadosNaProposta(msgSpan.getText().substring(msgSpan.getText().indexOf("Proposta")).replace("Proposta", "").trim(), null,null,null, proposta);
        salvarDadosPropostaCliente(cliente, proposta);

        WebElement botaoConfirmar = wait.until(ExpectedConditions.elementToBeClickable(By.xpath("//*[text()[contains(., 'Confirmar')]]")));
        botaoConfirmar.click();
    }

    private void adicionarPdfNaProposta(WebDriver driver, WebDriverWait wait) {
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//*[text()[contains(., 'Documentação para cadastro')]]")));

            Thread.sleep(3000);

            WebElement iconeAnexar = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("imgChLstP_000023_3")));

            Actions actions = new Actions(driver);
            actions.moveToElement(iconeAnexar).perform();

            WebElement menuAnexar = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("P_000023_3")));

            WebElement linkAnexarDocumento = menuAnexar.findElement(By.xpath(".//a[contains(text(), 'Anexar Documento')]"));
            linkAnexarDocumento.click();

            wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("ctl00_Cph_TBCP_1_uc_1_1_upModal")));

            File file = new File("src/main/resources/utils/contracheque.pdf");
            String absolutePath = file.getAbsolutePath();

            Thread.sleep(1000);

            List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
            boolean iframeFound = false;

            for (int i = 0; i < iframes.size(); i++) {
                driver.switchTo().defaultContent();

                driver.switchTo().frame(i);

                if (Objects.requireNonNull(driver.getPageSource()).contains("Upload de Arquivos")) {
                    iframeFound = true;
                    break;
                }
            }
            if (!iframeFound) {
                throw new RuntimeException("Não foi possível localizar o iframe do modal de upload!");
            }
            WebElement inputUpload = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//input[@type='file']"))
            );
            inputUpload.sendKeys(absolutePath);
            Thread.sleep(1000);


            WebElement botaoAdicionar = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//*[normalize-space(text())='Adicionar']"))
            );
            botaoAdicionar.click();

            Thread.sleep(1000);

            WebElement botaoAnexar = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//*[normalize-space(text())='Anexar']"))
            );
            botaoAnexar.click();

            Thread.sleep(1000);

            CrawlerUtils.interagirComAlert(driver);

            WebElement botaoFechar = wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath("//*[normalize-space(text())='Fechar']"))
            );
            botaoFechar.click();
        } catch (Exception ignored) {}
    }

    private void aprovarProposta(WebDriver driver, WebDriverWait wait) {
        driver.switchTo().parentFrame();

        WebElement botaoAprova = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//a[@id='BBApr_txt' and contains(text(), 'Aprova')]"))
        );

        Actions actions = new Actions(driver);
        actions.moveToElement(botaoAprova).click().perform();

        try {
            Thread.sleep(1000);
        } catch (Exception ignored){}
    }

    private void salvarDadosPropostaCliente(Cliente cliente, Proposta proposta) {
        Cliente finalCliente = cliente;
        cliente = Optional.ofNullable(cliente)
                .orElseGet(() -> {
                    Cliente c = clienteService.findByCpf(finalCliente.getCpf());
                    if (c == null) {
                        throw new RuntimeException("Cliente não encontrado no banco...");
                    }
                    return c;
        });
        if (proposta.getCliente() == null) {
            proposta.setCliente(cliente);
        }
        propostaService.salvarPropostaEAtualizarCliente(proposta, cliente);
        log.info("Proposta salva com dados Atualizados!");
    }

    private void acessarTelaEsteira(WebDriver driver, WebDriverWait wait) {
        ((JavascriptExecutor) driver).executeScript("document.activeElement.blur();");
        WebElement menuEsteira = null;
        try {
            List<WebElement> links = driver.findElements(By.tagName("a"));

            for (WebElement esteira : links) {
                if (esteira.getText().contains("Esteira")) {
                    menuEsteira = esteira;
                }
            }
        } catch (Exception e) {
            try {
                menuEsteira = wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.xpath("//*[contains(normalize-space(text()), 'Esteira')]")));
            } catch (Exception ignored) {}
        }
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", menuEsteira);

        WebElement opcaoProposta = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//a[contains(text(),'Aprovação / Consulta')]")));

        opcaoProposta.click();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void buscarProposta(WebDriver driver, WebDriverWait wait, String numeroProposta) {
        WebElement campoBusca = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.xpath("//span[text()='Nr. Proposta:']/following-sibling::input")));

        Actions actions = new Actions(driver);
        for (int i = 0; i < numeroProposta.length(); i++) {
            actions.moveToElement(campoBusca).click().sendKeys(Keys.BACK_SPACE).perform();
        }
        actions.moveToElement(campoBusca).sendKeys(numeroProposta).perform();

        WebElement btnPesquisar = wait.until(ExpectedConditions.elementToBeClickable(By.id("ctl00_Cph_AprCons_btnPesquisar")));
        btnPesquisar.click();
        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {}
    }

    private void obtemInformacoesValoresProposta(WebDriver driver, Proposta proposta) {
        try {
            if (driver.getPageSource().contains("Não há dados para a visualização"))
                return;

            String pageText = driver.findElement(By.tagName("body")).getText();
            String pageAuxStr = pageText.split("Nr Proposta Convênio")[1];
            pageAuxStr = pageAuxStr.split("CARTÃO")[0];
            String[] pageAuxSplt = pageAuxStr.split(" ");
            String valorParcela = pageAuxSplt[pageAuxSplt.length-1];
            String valorLiberado = pageAuxSplt[pageAuxSplt.length-3];

            if (proposta.getValorParcela() == null) {
                setaDadosNaProposta(null, CrawlerUtils.parseBrlToBigDecimal(valorLiberado), CrawlerUtils.parseBrlToBigDecimal(valorParcela), null, proposta);
                salvarDadosPropostaCliente(proposta.getCliente(), proposta);
            }
        } catch (Exception ignored) {}
    }

    private void processaPropostaECapturaLink(WebDriver driver, WebDriverWait wait, Proposta proposta) {
        String pageText = driver.findElement(By.tagName("body")).getText();

        if (pageText.contains("ANEXAR DOCUMENTOS")) {
            WebElement validaCodigoSiapeLink  = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("ANEXAR DOCUMENTOS")));
            validaCodigoSiapeLink.click();

            if (CrawlerUtils.interagirComAlert(driver))
                return;

            try {
                adicionarPdfNaProposta(driver, wait);
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript("if (document.activeElement) document.activeElement.blur();");
            } catch (Exception ignored) {}
            try {
                capturaLinkAssinatura(driver, wait, proposta);
                aprovarProposta(driver, wait);
            } catch (Exception ignored){}
        }
        else if (pageText.contains("PENDENCIA ANEXAR DOC")) {
            WebElement validaCodigoSiapeLink = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("PENDENCIA ANEXAR DOC")));
            validaCodigoSiapeLink.click();

            if (CrawlerUtils.interagirComAlert(driver))
                return;

            try {
                adicionarPdfNaProposta(driver, wait);
                JavascriptExecutor js = (JavascriptExecutor) driver;
                js.executeScript("if (document.activeElement) document.activeElement.blur();");
            } catch (Exception ignored) {}
            try {
                capturaLinkAssinatura(driver, wait, proposta);
                salvarDadosPropostaCliente(proposta.getCliente(), proposta);
                aprovarProposta(driver, wait);
                clicarVoltar(driver, wait);
            } catch (Exception ignored){}
        }
        else if (pageText.contains("VALIDA CODIGO SIAPE")) {
            if (CrawlerUtils.interagirComAlert(driver))
                return;
            capturaLinkAssinatura(driver, wait, proposta);
            salvarDadosPropostaCliente(proposta.getCliente(), proposta);
        }
        else if (pageText.contains("AGUARDANDO ASSINATURA CCB")) {
            WebElement validaCodigoSiapeLink = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("AGUARDANDO ASSINATURA CCB")));
            validaCodigoSiapeLink.click();
            if (CrawlerUtils.interagirComAlert(driver))
                return;
            clicarObservacao(driver, wait);
            encontrarIFrame(driver, wait);
            capturaLinkAssinatura(driver, wait, proposta);
            salvarDadosPropostaCliente(proposta.getCliente(), proposta);
            clicarFechar(driver, wait);
            clicarVoltar(driver, wait);
        }
        clicarVoltar(driver, wait);
    }

    private void clicarFechar(WebDriver driver, WebDriverWait wait) {
        try {
            WebElement voltar = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("Fechar")));
            voltar.click();
        } catch (Exception e) {
            driver.switchTo().parentFrame();
        }
    }

    private void encontrarIFrame(WebDriver driver, WebDriverWait wait) {
        List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
        boolean iframeFound = false;

        for (int i = 0; i < iframes.size(); i++) {
            driver.switchTo().defaultContent();

            driver.switchTo().frame(i);

            if (Objects.requireNonNull(driver.getPageSource()).contains("Link de Assinatura:")) {
                iframeFound = true;
                break;
            }
        }
        if (!iframeFound) {
            throw new RuntimeException("Não foi possível localizar o iframe do modal de upload!");
        }
    }

    private void clicarVoltar(WebDriver driver, WebDriverWait wait) {
        try {
            WebElement voltar = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("Voltar")));
            voltar.click();
        } catch (Exception e) {
         driver.switchTo().parentFrame();
        }
    }

    private void clicarObservacao(WebDriver driver, WebDriverWait wait) {
        try {
            WebElement voltar = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("Observação")));
            voltar.click();
        } catch (Exception e) {
            driver.switchTo().parentFrame();
        }
    }

    private void capturaLinkAssinatura(WebDriver driver, WebDriverWait wait, Proposta proposta) {
        try {
            WebElement validaCodigoSiapeLink = wait.until(ExpectedConditions.elementToBeClickable(By.linkText("VALIDA CODIGO SIAPE")));
            validaCodigoSiapeLink.click();

            if (CrawlerUtils.interagirComAlert(driver))
                return;


            WebElement documentos = wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//span[contains(text(),'DOCUMENTOS')]")));

            Actions actions = new Actions(driver);
            actions.moveToElement(documentos).click().perform();
        } catch (Exception ignored) {
        }

        String bodySplt = driver.findElement(By.tagName("body")).getText().split("Link de Assinatura:")[1];
        String linkAssinatura = bodySplt.split("Inserir Novas Observações")[0].trim();

        if (linkAssinatura.contains("\n") || linkAssinatura.contains("\r")) {
            String[] linhas = linkAssinatura.split("\\r?\\n");
            linkAssinatura = linhas[0];
        }

        setaDadosNaProposta(null,null,null, linkAssinatura, proposta);
        salvarDadosPropostaCliente(proposta.getCliente(), proposta);
    }

    private void setaDadosNaProposta(String numeroProposta, BigDecimal vlrLib, BigDecimal vlrParc, String linkAss, Proposta proposta) {
        if (numeroProposta != null)
            proposta.setNumeroProposta(numeroProposta);
        if (vlrLib != null)
            proposta.setValorLiberado(vlrLib);
        if (vlrParc != null)
            proposta.setValorParcela(vlrParc);
        if (linkAss != null)
            proposta.setLinkAssinatura(linkAss);

        StringBuilder sb = new StringBuilder();
        sb.append("Informações da propostas atualizadas - ");
        if (numeroProposta != null)
            sb.append("Numero: ").append(numeroProposta).append(" | ");
        if (vlrLib != null)
            sb.append("Valor Liberado: ").append(vlrLib).append(" | ");
        if (vlrParc != null)
            sb.append("Valor Parcela: ").append(vlrParc).append(" | ");
        if (linkAss != null)
            sb.append("Link: ").append(linkAss);

        log.info(sb.toString());
    }

}
