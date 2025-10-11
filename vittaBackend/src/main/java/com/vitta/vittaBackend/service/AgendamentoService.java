package com.vitta.vittaBackend.service;

import com.vitta.vittaBackend.dto.request.agendamento.AgendamentoAtualizarDTORequest;
import com.vitta.vittaBackend.dto.request.agendamento.AgendamentoDTORequest;
import com.vitta.vittaBackend.dto.request.medicamentoHistorico.RegistrarUsoDTORequest;
import com.vitta.vittaBackend.dto.response.agendamento.AgendamentoDTOResponse;
import com.vitta.vittaBackend.dto.response.agendamento.AgendamentoResumoDTOResponse;
import com.vitta.vittaBackend.dto.response.medicamento.MedicamentoResumoDTOResponse;
import com.vitta.vittaBackend.dto.response.medicamentoHistorico.MedicamentoHistoricoDTOResponse;
import com.vitta.vittaBackend.entity.Agendamento;
import com.vitta.vittaBackend.entity.MedicamentoHistorico;
import com.vitta.vittaBackend.entity.Tratamento;
import com.vitta.vittaBackend.entity.Usuario;
import com.vitta.vittaBackend.enums.TipoFrequencia;
import com.vitta.vittaBackend.enums.agendamento.AgendamentoStatus;
import com.vitta.vittaBackend.enums.agendamento.TipoDeAlerta;
import com.vitta.vittaBackend.enums.tratamento.TratamentoStatus;
import com.vitta.vittaBackend.repository.AgendamentoRepository;
import com.vitta.vittaBackend.entity.Medicamento;
import com.vitta.vittaBackend.repository.TratamentoRepository;
import com.vitta.vittaBackend.repository.UsuarioRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AgendamentoService {

    private final AgendamentoRepository agendamentoRepository;
    private final UsuarioRepository usuarioRepository;
    private final TratamentoRepository tratamentoRepository;
    private final ModelMapper modelMapper;
    private static final Logger logger = LoggerFactory.getLogger(AgendamentoService.class);

    public AgendamentoService(
            AgendamentoRepository agendamentoRepository,
            UsuarioRepository usuarioRepository,
            TratamentoRepository tratamentoRepository,
            ModelMapper modelMapper
    ) {
        this.agendamentoRepository = agendamentoRepository;
        this.usuarioRepository = usuarioRepository;
        this.tratamentoRepository = tratamentoRepository;
        this.modelMapper = modelMapper;
    }

    /**
     * Lista todos os agendamentos ativos de um usuário específico.
     * @param usuarioId O ID do usuário autenticado.
     * @return Uma lista de AgendamentoDTOResponse.
     */
    public List<AgendamentoDTOResponse> listarAgendamentosDoUsuario(Integer usuarioId) {
        return agendamentoRepository.listarAgendamentosAtivos(usuarioId)
                .stream()
                .map(AgendamentoDTOResponse::new)
                .collect(Collectors.toList());
    }

    /**
     * Busca um único agendamento pelo seu ID, garantindo que ele pertença ao usuário.
     * @param agendamentoId O ID do agendamento a ser buscado.
     * @param usuarioId O ID do usuário autenticado.
     * @return O AgendamentoDTOResponse correspondente.
     */
    public AgendamentoDTOResponse listarAgendamentoPorId(Integer agendamentoId, Integer usuarioId) {
        Agendamento agendamento = validarAgendamento(agendamentoId, usuarioId);
        return new AgendamentoDTOResponse(agendamento);
    }

    /**
     * Cria um novo agendamento para o usuário autenticado.
     * O ID do usuário é obtido do contexto de segurança, não do DTO.
     * @param agendamentoDTORequest DTO com os dados do novo agendamento.
     * @param usuarioId O ID do usuário autenticado.
     * @return O AgendamentoDTOResponse do novo agendamento.
     */
    @Transactional
    public AgendamentoDTOResponse criarAgendamento(AgendamentoDTORequest agendamentoDTORequest, Integer usuarioId) {
        Usuario usuario = usuarioRepository.getReferenceById(usuarioId);

        Tratamento tratamento = tratamentoRepository.listarTratamentoPorId(agendamentoDTORequest.getTratamentoId(), usuarioId);
        // fazer as verificações de regra de negócio para garantir
        // que não crie agendamentos associados a tratamentos inválidos
        if (tratamento.getStatus() != TratamentoStatus.ATIVO) {
            throw new IllegalArgumentException("Não é possível criar agendamento para um tratamento inativo.");
        }
        LocalDate dataInicioTratamento = tratamento.getDataDeInicio();
        LocalDate dataTerminoTratamento = tratamento.getDataDeTermino();

        // conversão para LocalDateTime para poder usar as
        // funções necessários para validar as condicionais
        LocalDateTime inicioTratamento = dataInicioTratamento.atStartOfDay();

        if (agendamentoDTORequest.getHorarioDoAgendamento().isBefore(inicioTratamento)) {
            throw new IllegalArgumentException("A data do agendamento não pode ser anterior à data de início do tratamento.");
        }
        if (dataTerminoTratamento != null) {
            LocalDateTime fimTratamento = dataTerminoTratamento.atTime(LocalTime.MAX);
            if (agendamentoDTORequest.getHorarioDoAgendamento().isAfter(fimTratamento)) {
                throw new IllegalArgumentException("A data do agendamento não pode ser posterior à data de término do tratamento.");
            }
        }

        if (tratamento == null) {
            throw new EntityNotFoundException("Tratamento não encontrado ou não pertence a este usuário.");
        }

        Agendamento agendamento = new Agendamento();

        agendamento.setHorarioDoAgendamento(agendamentoDTORequest.getHorarioDoAgendamento());

        if (agendamentoDTORequest.getTipoDeAlerta() != null) {
            agendamento.setTipoDeAlerta(
                    TipoDeAlerta.fromCodigo(agendamentoDTORequest.getTipoDeAlerta())
            );
        }

        agendamento.setTratamento(tratamento);
        agendamento.setUsuario(usuario);

        Agendamento agendamentoSalvo = agendamentoRepository.save(agendamento);
        return new AgendamentoDTOResponse(agendamentoSalvo);
    }

    /**
     * Atualiza um agendamento existente, garantindo que ele pertença ao usuário.
     * @param agendamentoId O ID do agendamento a ser atualizado.
     * @param usuarioId O ID do usuário autenticado.
     * @param agendamentoAtualizarDTORequest DTO com os dados de atualização.
     * @return O AgendamentoDTOResponse atualizado.
     */
    @Transactional
    public AgendamentoDTOResponse atualizarAgendamento(Integer agendamentoId, Integer usuarioId, AgendamentoAtualizarDTORequest agendamentoAtualizarDTORequest) {
        Agendamento agendamentoBuscado = validarAgendamento(agendamentoId, usuarioId);

            agendamentoBuscado.setHorarioDoAgendamento(agendamentoAtualizarDTORequest.getHorarioDoAgendamento());

            if (agendamentoAtualizarDTORequest.getTipoDeAlerta() != null) {
                agendamentoBuscado.setTipoDeAlerta(
                        TipoDeAlerta.fromCodigo(agendamentoAtualizarDTORequest.getTipoDeAlerta())
                );
            }

            // converte o Integer que vem do AtualizarDTORequest para o Enum AgendamentoStatus
            if (agendamentoAtualizarDTORequest.getStatus() != null) {
                agendamentoBuscado.setStatus(
                        AgendamentoStatus.fromCodigo(agendamentoAtualizarDTORequest.getStatus())
                );
            }

            Agendamento agendamentoRecebido = agendamentoRepository.save(agendamentoBuscado);
            return new AgendamentoDTOResponse(agendamentoRecebido);
    }

    /**
     * Realiza a exclusão lógica de um agendamento, garantindo que ele pertença ao usuário.
     * @param agendamentoId O ID do agendamento a ser deletado.
     * @param usuarioId O ID do usuário autenticado.
     */
    @Transactional
    public void deletarAgendamento(Integer agendamentoId, Integer usuarioId) { agendamentoRepository.apagarLogicoAgendamento(agendamentoId, usuarioId); }


    /**
     * Valida a existência de um agendamento e a sua posse pelo usuário especificado.
     * <p>
     * Este é um método auxiliar privado que busca um agendamento no repositório
     * usando tanto o ID do agendamento quanto o ID do usuário. Ele serve como uma
     * verificação de segurança e existência antes de qualquer operação de atualização ou exclusão.
     *
     * @param agendamentoId O ID do agendamento a ser validado e buscado.
     * @param usuarioId O ID do usuário que deve ser o proprietário do agendamento.
     * @return A entidade {@link Agendamento} encontrada, caso seja válida e pertença ao usuário.
     * @throws RuntimeException se o agendamento não for encontrado ou não pertencer ao usuário especificado.
     */
    public Agendamento validarAgendamento(Integer agendamentoId, Integer usuarioId) {
        Agendamento agendamento = agendamentoRepository.listarAgendamentoPorId(agendamentoId, usuarioId);
        if (agendamento == null) {
            throw new RuntimeException("Agendamento não encontrado ou inativo.");
        }
        return agendamento;
    }

    /**
     * Lógica principal para gerar a lista completa de agendamentos com base nas regras de um tratamento.
     * <p>
     * Este método itera do primeiro ao último dia do tratamento e, para cada dia, calcula
     * os horários das doses de acordo com o tipo de frequência definido (intervalo de horas
     * ou horários específicos). Ele utiliza o método auxiliar {@link #criarAgendamento}
     * para instanciar cada agendamento individual.
     *
     * @param tratamento A entidade {@link Tratamento} já salva, contendo as regras de agendamento.
     * @return Uma lista de novas entidades {@link Agendamento}, prontas para serem salvas no banco de dados.
     */
    public List<Agendamento> gerarAgendamentosParaTratamento(Tratamento tratamento, TipoDeAlerta tipoDeAlerta) {
        List<Agendamento> agendamentos = new ArrayList<>();

        if (tratamento.getDataDeTermino() == null || tratamento.getDataDeTermino().isBefore(tratamento.getDataDeInicio())) {
            return agendamentos;
        }

        LocalDate dataCorrente = tratamento.getDataDeInicio();

        while (!dataCorrente.isAfter(tratamento.getDataDeTermino())) {
            if (tratamento.getTipoDeFrequencia() == TipoFrequencia.INTERVALO_HORAS) {

                Integer intervalo = tratamento.getIntervaloEmHoras();
                if(intervalo == null || intervalo <= 0) {
                    logger.warn("Intervalo de horas inválido ({}). Pulando geração para o dia {} no tratamento ID {}",
                            intervalo, dataCorrente, tratamento.getId());

                    dataCorrente = dataCorrente.plusDays(1);
                    continue;
                }

                // define o primeiro horário do dia
                LocalDateTime proximoAgendamento = dataCorrente.atTime(1, 0);

                // loop para gerar agendamentos ENQUANTO eles pertencerem à dataCorrente
                while (proximoAgendamento.toLocalDate().isEqual(dataCorrente)) {
                    // adiciona o agendamento apenas se ele não ultrapassar a data e hora de término do tratamento
                    if (tratamento.getDataDeTermino() != null) {
                        LocalDateTime dataHoraTermino = tratamento.getDataDeTermino().atTime(LocalTime.MAX);
                        if (proximoAgendamento.isAfter(dataHoraTermino)) {
                            break; // para o loop se o próximo agendamento já passou do fim do tratamento
                        }
                    }

                    agendamentos.add(criarAgendamento(tratamento, proximoAgendamento, tipoDeAlerta));

                    proximoAgendamento = proximoAgendamento.plusHours(intervalo);
                }

            } else if (tratamento.getTipoDeFrequencia() == TipoFrequencia.HORARIOS_ESPECIFICOS) {
                String[] horarios = tratamento.getHorariosEspecificos().split(",");
                for (String horaStr : horarios) {
                    LocalTime horario = LocalTime.parse(horaStr.trim());
                    LocalDateTime dataHoraAgendamento = LocalDateTime.of(dataCorrente, horario);

                    // passa o tipoDeAlerta para o método criador
                    agendamentos.add(criarAgendamento(tratamento, dataHoraAgendamento, tipoDeAlerta));
                }
            }
            dataCorrente = dataCorrente.plusDays(1);
        }

        return agendamentos;
    }

    /**
     * Versão modificada que gera agendamentos apenas a partir de uma data de início,
     * que por padrão será "hoje".
     */
    public List<Agendamento> gerarAgendamentosFuturos(Tratamento tratamento, TipoDeAlerta tipoDeAlerta) {
        List<Agendamento> agendamentos = new ArrayList<>();

        if (tratamento.getDataDeTermino() == null || tratamento.getDataDeTermino().isBefore(tratamento.getDataDeInicio())) {
            return agendamentos;
        }

        LocalDate dataCorrente = tratamento.getDataDeInicio();

        while (!dataCorrente.isAfter(tratamento.getDataDeTermino())) {
            if (tratamento.getTipoDeFrequencia() == TipoFrequencia.INTERVALO_HORAS) {

                Integer intervalo = tratamento.getIntervaloEmHoras();
                if(intervalo == null || intervalo <= 0) {
                    logger.warn("Intervalo de horas inválido ({}). Pulando geração para o dia {} no tratamento ID {}",
                            intervalo, dataCorrente, tratamento.getId());

                    dataCorrente = dataCorrente.plusDays(1);
                    continue;
                }

                // define o primeiro horário do dia
                LocalDateTime proximoAgendamento = dataCorrente.atTime(1, 0);

                // loop para gerar agendamentos ENQUANTO eles pertencerem à dataCorrente
                while (proximoAgendamento.toLocalDate().isEqual(dataCorrente)) {
                    // adiciona o agendamento apenas se ele não ultrapassar a data e hora de término do tratamento
                    if (tratamento.getDataDeTermino() != null) {
                        LocalDateTime dataHoraTermino = tratamento.getDataDeTermino().atTime(LocalTime.MAX);
                        if (proximoAgendamento.isAfter(dataHoraTermino)) {
                            break; // para o loop se o próximo agendamento já passou do fim do tratamento
                        }
                    }

                    agendamentos.add(criarAgendamento(tratamento, proximoAgendamento, tipoDeAlerta));

                    proximoAgendamento = proximoAgendamento.plusHours(intervalo);
                }

            } else if (tratamento.getTipoDeFrequencia() == TipoFrequencia.HORARIOS_ESPECIFICOS) {
                String[] horarios = tratamento.getHorariosEspecificos().split(",");
                for (String horaStr : horarios) {
                    LocalTime horario = LocalTime.parse(horaStr.trim());
                    LocalDateTime dataHoraAgendamento = LocalDateTime.of(dataCorrente, horario);

                    // passa o tipoDeAlerta para o método criador
                    agendamentos.add(criarAgendamento(tratamento, dataHoraAgendamento, tipoDeAlerta));
                }
            }
            dataCorrente = dataCorrente.plusDays(1);
        }

        return agendamentos;
    }

    /**
     * Método auxiliar (factory) para criar e configurar uma única instância da entidade {@link Agendamento}.
     * <p>
     * Centraliza a criação do objeto, definindo os valores padrão como o status inicial {@code PENDENTE}.
     *
     * @param tratamento O tratamento "pai" ao qual este agendamento pertence.
     * @param dataHora   A data e hora exata em que este agendamento deve ocorrer.
     * @return A nova entidade {@link Agendamento}, pronta para ser adicionada à lista de geração.
     */
    public Agendamento criarAgendamento(Tratamento tratamento, LocalDateTime dataHora, TipoDeAlerta tipoDeAlerta) {
        Agendamento agendamento = new Agendamento();

        agendamento.setTratamento(tratamento);
        agendamento.setHorarioDoAgendamento(dataHora);
        agendamento.setStatus(AgendamentoStatus.PENDENTE);
        agendamento.setTipoDeAlerta(tipoDeAlerta);
        agendamento.setUsuario(tratamento.getUsuario());

        return agendamento;
    }

    /**
     * Conclui um agendamento de medicamento, alterando seu status para TOMADO e registrando o histórico do uso.
     * <p>
     * Este método orquestra a lógica de negócio para confirmar a toma de um medicamento. Ele primeiro valida
     * se o agendamento alvo existe e se está com o status PENDENTE. Se as condições forem atendidas,
     * o status do agendamento é atualizado e um novo registro {@link MedicamentoHistorico} é criado com os
     * detalhes fornecidos e associado ao usuário que realizou a ação.
     * <p>
     *
     * @param registroDoUso DTO contendo os dados da conclusão.
     * @param usuarioId     O ID do usuário autenticado que está realizando a operação.
     * @return Um {@link MedicamentoHistoricoDTOResponse} representando o novo registro de histórico que foi criado.
     * @throws EntityNotFoundException se o agendamento com o ID fornecido não for encontrado.
     * @throws IllegalStateException se o agendamento já foi concluído ou cancelado (não está com status PENDENTE).
     */
    @Transactional
    public MedicamentoHistoricoDTOResponse concluirAgendamento(Integer agendamentoId, RegistrarUsoDTORequest registroDoUso, Integer usuarioId) {

        Usuario usuario = usuarioRepository.getReferenceById(usuarioId);

        Agendamento agendamento = agendamentoRepository.findById(agendamentoId)
                .orElseThrow(() -> new EntityNotFoundException("Agendamento não encontrado..."));

        if (agendamento.getStatus() != AgendamentoStatus.PENDENTE) {
            throw new IllegalStateException("Este agendamento já foi concluído ou cancelado.");
        }

        MedicamentoHistorico novoHistorico = new MedicamentoHistorico();
        novoHistorico.setAgendamento(agendamento);
        novoHistorico.setHoraDoUso(registroDoUso.getHoraDoUso());
        novoHistorico.setDoseTomada(registroDoUso.getDoseTomada());
        novoHistorico.setObservacao(registroDoUso.getObservacao());
        novoHistorico.setUsuario(usuario);

        agendamento.setMedicamentoHistorico(novoHistorico);
        agendamento.setStatus(AgendamentoStatus.TOMADO);

        Agendamento agendamentoSalvo = agendamentoRepository.save(agendamento);

        return converterHistoricoParaDTO(agendamentoSalvo.getMedicamentoHistorico());
    }


    /**
     * Converte uma entidade {@link MedicamentoHistorico} em seu respectivo DTO de resposta, {@link MedicamentoHistoricoDTOResponse}.
     * <p>
     * Este método atua como um mapeador, transformando o objeto do domínio (entidade JPA) em um
     * Objeto de Transferência de Dados (DTO) seguro para ser exposto na API. Ele seleciona os campos
     * relevantes do histórico e também converte entidades aninhadas, como {@link Agendamento} e {@link Medicamento},
     * para seus respectivos DTOs de resumo.
     * <p>
     * Se o objeto de histórico fornecido for nulo, o método retornará nulo.
     *
     * @param historico A entidade {@link MedicamentoHistorico} a ser convertida.
     * @return O {@link MedicamentoHistoricoDTOResponse} correspondente, ou null se a entrada for nula.
     */
    private MedicamentoHistoricoDTOResponse converterHistoricoParaDTO(MedicamentoHistorico historico) {
        if (historico == null) {
            return null;
        }

        MedicamentoHistoricoDTOResponse dto = new MedicamentoHistoricoDTOResponse();
        dto.setId(historico.getId());
        dto.setHoraDoUso(historico.getHoraDoUso());
        dto.setDoseTomada(historico.getDoseTomada());
        dto.setObservacao(historico.getObservacao());
        dto.setHistoricoStatus(historico.getHistoricoStatus());

        if (historico.getAgendamento() != null) {
            dto.setAgendamento(new AgendamentoResumoDTOResponse(historico.getAgendamento()));
        }

        if (historico.getAgendamento() != null && historico.getAgendamento().getTratamento().getMedicamento() != null) {
            dto.setMedicamento(new MedicamentoResumoDTOResponse(historico.getAgendamento().getTratamento().getMedicamento()));
        }

        return dto;
    }
}
