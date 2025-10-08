package com.vitta.vittaBackend.service;

import com.vitta.vittaBackend.dto.request.tratamento.TratamentoAtualizarDTORequest;
import com.vitta.vittaBackend.dto.request.tratamento.TratamentoDTORequest;
import com.vitta.vittaBackend.dto.response.tratamento.TratamentoDTOResponse;
import com.vitta.vittaBackend.entity.Agendamento;
import com.vitta.vittaBackend.entity.Medicamento;
import com.vitta.vittaBackend.entity.Tratamento;
import com.vitta.vittaBackend.entity.Usuario;
import com.vitta.vittaBackend.enums.TipoFrequencia;
import com.vitta.vittaBackend.enums.tratamento.TratamentoStatus;
import com.vitta.vittaBackend.enums.agendamento.AgendamentoStatus;
import com.vitta.vittaBackend.enums.agendamento.TipoDeAlerta;
import com.vitta.vittaBackend.repository.AgendamentoRepository;
import com.vitta.vittaBackend.repository.MedicamentoRepository;
import com.vitta.vittaBackend.repository.TratamentoRepository;
import com.vitta.vittaBackend.repository.UsuarioRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Camada de serviço para gerenciar a lógica de negócio dos Tratamentos.
 * Responsável por criar tratamentos e gerar os agendamentos correspondentes.
 */
@Service
public class TratamentoService {

    private final TratamentoRepository tratamentoRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final MedicamentoRepository medicamentoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AgendamentoService agendamentoService;
    private final ModelMapper modelMapper;

    public TratamentoService(
            TratamentoRepository tratamentoRepository,
            AgendamentoRepository agendamentoRepository,
            MedicamentoRepository medicamentoRepository,
            UsuarioRepository usuarioRepository,
            AgendamentoService agendamentoService,
            ModelMapper modelMapper
    ) {
        this.tratamentoRepository = tratamentoRepository;
        this.agendamentoRepository = agendamentoRepository;
        this.medicamentoRepository = medicamentoRepository;
        this.usuarioRepository = usuarioRepository;
        this.agendamentoService = agendamentoService;
        this.modelMapper = modelMapper;
    }

    /**
     * Retorna uma lista de todos os tratamentos ativos de um usuário específico.
     * @param usuarioId O ID do usuário autenticado.
     * @return Uma lista de {@link TratamentoDTOResponse}.
     */
    public List<TratamentoDTOResponse> listarTratamentos(Integer usuarioId) {
        return tratamentoRepository.listarTratamentos(usuarioId)
                .stream()
                .map(TratamentoDTOResponse::new)
                .collect(Collectors.toList());
    }

    /**
     * Busca um tratamento pelo seu ID, garantindo que pertença ao usuário.
     * @param tratamentoId O ID do tratamento a ser buscado.
     * @param usuarioId O ID do usuário autenticado.
     * @return O {@link TratamentoDTOResponse} correspondente ao ID.
     */
    public TratamentoDTOResponse listarTratamentoPorId(Integer tratamentoId, Integer usuarioId) {
        Tratamento tratamento = validarTratamento(tratamentoId, usuarioId);
        return new TratamentoDTOResponse(tratamento);
    }

    /**
     * Cadastra um novo tratamento e gera os agendamentos correspondentes para o usuário autenticado.
     * @param tratamentoDTORequest O DTO contendo os dados do novo tratamento.
     * @param usuarioId O ID do usuário autenticado (vindo do token).
     * @return O {@link TratamentoDTOResponse} do tratamento recém-criado.
     */
    @Transactional
    public TratamentoDTOResponse cadastrarTratamento(TratamentoDTORequest tratamentoDTORequest, Integer usuarioId) {

        // linkar com Medicamento e Usuario já cadastrados passando o ID no DTORequest
        Medicamento medicamento = medicamentoRepository.listarMedicamentoPorId(
                tratamentoDTORequest.getMedicamentoId(),
                usuarioId
        );

        if (medicamento == null) {
            throw new EntityNotFoundException("Medicamento não encontrado ou não pertence a este usuário.");
        }

        Usuario usuario = usuarioRepository.getReferenceById(usuarioId);

        Tratamento tratamento = new Tratamento();

        tratamento.setMedicamento(medicamento);
        tratamento.setDosagem(tratamentoDTORequest.getDosagem());
        tratamento.setInstrucoes(tratamentoDTORequest.getInstrucoes());
        tratamento.setDataDeInicio(tratamentoDTORequest.getDataDeInicio());
        tratamento.setDataDeTermino(tratamentoDTORequest.getDataDeTermino());

        tratamento.setUsuario(usuario);

        if (tratamentoDTORequest.getTipoDeFrequencia() != null) {
            tratamento.setTipoDeFrequencia(
                    TipoFrequencia.fromCodigo(tratamentoDTORequest.getTipoDeFrequencia())
            );
        }

        tratamento.setIntervaloEmHoras(tratamentoDTORequest.getIntervaloEmHoras());
        tratamento.setHorariosEspecificos(tratamentoDTORequest.getHorariosEspecificos());


        //guardar o tipoDeAlerta em uma variável local para ser usada como parâmetro em outro método
        TipoDeAlerta tipoDeAlertaEscolhido;
        if (tratamentoDTORequest.getTipoDeAlerta() != null) {
            tipoDeAlertaEscolhido = TipoDeAlerta.fromCodigo(tratamentoDTORequest.getTipoDeAlerta());
        } else {
            //se nenhum código for enviado, usa um enum padrão
            tipoDeAlertaEscolhido = TipoDeAlerta.NOTIFICACAO_PUSH;
        }

        // gerar e salvar os agendamentos
        List<Agendamento> agendamentos = agendamentoService.gerarAgendamentosParaTratamento(tratamento, tipoDeAlertaEscolhido);
        if (agendamentos != null && !agendamentos.isEmpty()) {
            tratamento.setAgendamentos(agendamentos);
        }

        Tratamento tratamentoSalvo = tratamentoRepository.save(tratamento);
        return new TratamentoDTOResponse(tratamentoSalvo);
    }

    /**
     * Atualiza um tratamento existente e re-gera os agendamentos futuros se necessário.
     * @param tratamentoId O ID do tratamento a ser atualizado.
     * @param usuarioId O ID do usuário autenticado.
     * @param tratamentoAtualizarDTORequest O DTO com os novos dados.
     * @return O {@link TratamentoDTOResponse} da entidade atualizada.
     */
    @Transactional
    public TratamentoDTOResponse atualizarTratamento(Integer tratamentoId, Integer usuarioId, TratamentoAtualizarDTORequest tratamentoAtualizarDTORequest) {
        Tratamento tratamentoExistente = validarTratamento(tratamentoId, usuarioId);

        boolean regerarAgendamentos = false;
        TipoDeAlerta tipoDeAlertaParaRegeracao = null;

        if (tratamentoAtualizarDTORequest.getDosagem() != null) {
            tratamentoExistente.setDosagem(tratamentoAtualizarDTORequest.getDosagem());
        }
        if (tratamentoAtualizarDTORequest.getInstrucoes() != null) {
            tratamentoExistente.setInstrucoes(tratamentoAtualizarDTORequest.getInstrucoes());
        }

        // fazer verificação para ver se a estrutura do agendamento mudou (datas, frequência)
        if (tratamentoAtualizarDTORequest.getDataDeInicio() != null && !tratamentoAtualizarDTORequest.getDataDeInicio().equals(tratamentoExistente.getDataDeInicio())) {
            tratamentoExistente.setDataDeInicio(tratamentoAtualizarDTORequest.getDataDeInicio());
            regerarAgendamentos = true;
        }
        if (tratamentoAtualizarDTORequest.getDataDeTermino() != null && !tratamentoAtualizarDTORequest.getDataDeTermino().equals(tratamentoExistente.getDataDeTermino())) {
            tratamentoExistente.setDataDeTermino(tratamentoAtualizarDTORequest.getDataDeTermino());
            regerarAgendamentos = true;
        }
        if (tratamentoAtualizarDTORequest.getTipoDeFrequencia() != null) {
            TipoFrequencia novaFrequencia = TipoFrequencia.fromCodigo(tratamentoAtualizarDTORequest.getTipoDeFrequencia());
            if (novaFrequencia != tratamentoExistente.getTipoDeFrequencia()) {
                tratamentoExistente.setTipoDeFrequencia(novaFrequencia);
                regerarAgendamentos = true;
            }
            // sempre atualiza os campos de frequência se o tipo for enviado
            tratamentoExistente.setIntervaloEmHoras(tratamentoAtualizarDTORequest.getIntervaloEmHoras());
            tratamentoExistente.setHorariosEspecificos(tratamentoAtualizarDTORequest.getHorariosEspecificos());
        }

        // verificar se o tipo de alerta mudou (isso também força a regeração)
        if (tratamentoAtualizarDTORequest.getTipoDeAlerta() != null) {
            tipoDeAlertaParaRegeracao = TipoDeAlerta.fromCodigo(tratamentoAtualizarDTORequest.getTipoDeAlerta());
            regerarAgendamentos = true;
        } else if (regerarAgendamentos) {
            // se precisa regerar, mas o alerta não foi alterado, precisamos buscar o alerta atual
            // para manter a consistência.
            tipoDeAlertaParaRegeracao = tratamentoExistente.getAgendamentos().stream()
                    .findFirst()
                    .map(Agendamento::getTipoDeAlerta)
                    .orElse(TipoDeAlerta.NOTIFICACAO_PUSH);
        }

        if (regerarAgendamentos) {
            // remover os agendamentos futuros e pendentes DA COLEÇÃO em memória.
            // o `orphanRemoval=true` cuidará da exclusão no banco.
            tratamentoExistente.getAgendamentos().removeIf(agendamento ->
                    agendamento.getStatus() == AgendamentoStatus.PENDENTE
            );

            // Passo 2: Gerar os novos agendamentos (idealmente a partir de hoje).
            // Criaremos uma versão melhorada do método de geração.
            List<Agendamento> novosAgendamentos = agendamentoService.gerarAgendamentosFuturos(tratamentoExistente, tipoDeAlertaParaRegeracao);

            // Passo 3: Adicionar os novos agendamentos à coleção.
            if (novosAgendamentos != null && !novosAgendamentos.isEmpty()) {
                tratamentoExistente.getAgendamentos().addAll(novosAgendamentos);
            }
        }

        Tratamento tratamentoAtualizado = tratamentoRepository.save(tratamentoExistente);
        return new TratamentoDTOResponse(tratamentoAtualizado);
    }

    /**
     * Realiza a exclusão lógica de um tratamento, garantindo que ele pertença ao usuário.
     * @param tratamentoId O ID do tratamento a ser desativado.
     * @param usuarioId O ID do usuário autenticado.
     */
    @Transactional
    public void deletarLogico(Integer tratamentoId, Integer usuarioId) {
        Tratamento tratamento = validarTratamento(tratamentoId, usuarioId);

        //isso aki apaga todos os agendamentos com status 'PENDENTE'que haviam
        // sido gerados para o Tratamento que está sendo apagado
        tratamento.getAgendamentos().removeIf(agendamento ->
                agendamento.getStatus() == AgendamentoStatus.PENDENTE);

        tratamento.setStatus(TratamentoStatus.CANCELADO);
        tratamentoRepository.save(tratamento);
    }

    /**
     * Valida a existência de um tratamento e sua posse pelo usuário.
     * @param tratamentoId o ID do tratamento a ser validado.
     * @param usuarioId O ID do usuário que deve ser o proprietário.
     * @return A entidade {@link Tratamento} encontrada.
     * @throws EntityNotFoundException se o tratamento não for encontrado ou não pertencer ao usuário.
     */
    private Tratamento validarTratamento(Integer tratamentoId, Integer usuarioId) {
        Tratamento tratamento = tratamentoRepository.listarTratamentoPorId(tratamentoId, usuarioId);

        if (tratamento == null) {
            throw new EntityNotFoundException("Tratamento não encontrado com o ID: " + tratamentoId);
        }

        return tratamento;
    }

}
