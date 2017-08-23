// Copyright (c) YugaByte, Inc.

#include "yb/cqlserver/cql_service.h"

#include <thread>

#include "yb/client/client.h"
#include "yb/gutil/strings/join.h"

#include "yb/cqlserver/cql_processor.h"
#include "yb/cqlserver/cql_rpc.h"
#include "yb/cqlserver/cql_server.h"

#include "yb/gutil/strings/substitute.h"
#include "yb/rpc/rpc_context.h"
#include "yb/tserver/tablet_server.h"

#include "yb/util/bytes_formatter.h"
#include "yb/util/mem_tracker.h"

DEFINE_int64(cql_service_max_prepared_statement_size_bytes, 0,
             "The maximum amount of memory the CQL proxy should use to maintain prepared "
             "statements. 0 or negative means unlimited.");
DEFINE_int32(cql_ybclient_reactor_threads, 24,
             "The number of reactor threads to be used for processing ybclient "
             "requests originating in the cql layer");

namespace yb {
namespace cqlserver {

const char* const kRoleColumnNameSaltedHash = "salted_hash";

using std::shared_ptr;
using std::string;
using std::unique_ptr;
using std::vector;
using strings::Substitute;
using yb::client::YBClientBuilder;
using yb::client::YBSchema;
using yb::client::YBSession;
using yb::client::YBMetaDataCache;
using yb::rpc::InboundCall;

CQLServiceImpl::CQLServiceImpl(CQLServer* server, const CQLServerOptions& opts)
    : CQLServerServiceIf(server->metric_entity()),
      server_(server),
      next_available_processor_(processors_.end()),
      messenger_(server->messenger()),
      cql_rpcserver_env_(new CQLRpcServerEnv(server->first_rpc_address().address().to_string(),
                                             opts.broadcast_rpc_address)) {
  // TODO(ENG-446): Handle metrics for all the methods individually.
  // Setup client.
  SetUpYBClient(opts, server->metric_entity());
  cql_metrics_ = std::make_shared<CQLMetrics>(server->metric_entity());

  // Setup prepared statements' memory tracker. Add garbage-collect function to delete least
  // recently used statements when limit is hit.
  prepared_stmts_mem_tracker_ = MemTracker::CreateTracker(
      FLAGS_cql_service_max_prepared_statement_size_bytes > 0 ?
      FLAGS_cql_service_max_prepared_statement_size_bytes : -1,
      "CQL prepared statements' memory usage", server->mem_tracker());
  prepared_stmts_mem_tracker_->AddGcFunction(
      std::bind(&CQLServiceImpl::DeleteLruPreparedStatement, this));

  auth_prepared_stmt_ = std::make_shared<sql::Statement>(
      "",
      // TODO: enhance this once we need the other fields to create an AuthenticatedUser.
      Substitute("SELECT $0 FROM system_auth.roles WHERE role = ?", kRoleColumnNameSaltedHash));
}

void CQLServiceImpl::SetUpYBClient(
    const CQLServerOptions& opts, const scoped_refptr<MetricEntity>& metric_entity) {
  // Build cloud_info_pb.
  CloudInfoPB cloud_info_pb;
  cloud_info_pb.set_placement_cloud(opts.placement_cloud);
  cloud_info_pb.set_placement_region(opts.placement_region);
  cloud_info_pb.set_placement_zone(opts.placement_zone);

  YBClientBuilder client_builder;
  client_builder.set_client_name("cql_ybclient");
  if (server_->tserver()) {
    client_builder.set_tserver_uuid(server_->tserver()->permanent_uuid());
  }
  client_builder.default_rpc_timeout(MonoDelta::FromSeconds(kRpcTimeoutSec));
  client_builder.add_master_server_addr(opts.master_addresses_flag);
  client_builder.set_metric_entity(metric_entity);
  client_builder.set_num_reactors(FLAGS_cql_ybclient_reactor_threads);
  client_builder.set_cloud_info_pb(cloud_info_pb);
  CHECK_OK(client_builder.Build(&client_));
  // Add proxy to call local tserver if available.
  if (server_->tserver() != nullptr && server_->tserver()->proxy() != nullptr) {
    client_->AddTabletServerProxy(
        server_->tserver()->permanent_uuid(), server_->tserver()->proxy());
  }
  metadata_cache_ = std::make_shared<YBMetaDataCache>(client_);
}

void CQLServiceImpl::Handle(yb::rpc::InboundCallPtr inbound_call) {
  TRACE("Handling the CQL call");
  // Collect the call.
  CQLInboundCall* cql_call = down_cast<CQLInboundCall*>(CHECK_NOTNULL(inbound_call.get()));
  if (cql_call->TryResume()) {
    // This is a continuation/callback from a previous request.
    // Call the call back, and we are done.
    return;
  }
  DVLOG(4) << "Handling " << cql_call->ToString();

  // Process the call.
  MonoTime start = MonoTime::Now(MonoTime::FINE);
  CQLProcessor *processor = GetProcessor();
  CHECK(processor != nullptr);
  MonoTime got_processor = MonoTime::Now(MonoTime::FINE);
  cql_metrics_->time_to_get_cql_processor_->Increment(
      got_processor.GetDeltaSince(start).ToMicroseconds());
  processor->ProcessCall(std::move(inbound_call));
}

CQLProcessor *CQLServiceImpl::GetProcessor() {
  CQLProcessorListPos pos;
  {
    // Retrieve the next available processor. If none is available, allocate a new slot in the list.
    // Then create the processor outside the mutex below.
    std::lock_guard<std::mutex> guard(processors_mutex_);
    pos = (next_available_processor_ != processors_.end() ?
           next_available_processor_++ : processors_.emplace(processors_.end()));
  }

  if (pos->get() == nullptr) {
    pos->reset(new CQLProcessor(this, pos));
  }
  return pos->get();
}

void CQLServiceImpl::ReturnProcessor(const CQLProcessorListPos& pos) {
  // Put the processor back before the next available one.
  std::lock_guard<std::mutex> guard(processors_mutex_);
  processors_.splice(next_available_processor_, processors_, pos);
  next_available_processor_ = pos;
}

shared_ptr<CQLStatement> CQLServiceImpl::AllocatePreparedStatement(
    const CQLMessage::QueryId& query_id, const string& keyspace, const string& sql_stmt) {
  // Get exclusive lock before allocating a prepared statement and updating the LRU list.
  std::lock_guard<std::mutex> guard(prepared_stmts_mutex_);

  shared_ptr<CQLStatement> stmt;
  const auto itr = prepared_stmts_map_.find(query_id);
  if (itr == prepared_stmts_map_.end()) {
    // Allocate the prepared statement placeholder that multiple clients trying to prepare the same
    // statement to contend on. The statement will then be prepared by one client while the rest
    // wait for the results.
    stmt = prepared_stmts_map_.emplace(
        query_id, std::make_shared<CQLStatement>(
            keyspace, sql_stmt, prepared_stmts_list_.end())).first->second;
    InsertLruPreparedStatementUnlocked(stmt);
  } else {
    // Return existing statement if found.
    stmt = itr->second;
    MoveLruPreparedStatementUnlocked(stmt);
  }

  VLOG(1) << "InsertPreparedStatement: CQL prepared statement cache count = "
          << prepared_stmts_map_.size() << "/" << prepared_stmts_list_.size()
          << ", memory usage = " << prepared_stmts_mem_tracker_->consumption();

  return stmt;
}

shared_ptr<const CQLStatement> CQLServiceImpl::GetPreparedStatement(
    const CQLMessage::QueryId& query_id) {
  // Get exclusive lock before looking up a prepared statement and updating the LRU list.
  std::lock_guard<std::mutex> guard(prepared_stmts_mutex_);

  const auto itr = prepared_stmts_map_.find(query_id);
  if (itr == prepared_stmts_map_.end()) {
    return nullptr;
  }

  shared_ptr<CQLStatement> stmt = itr->second;
  MoveLruPreparedStatementUnlocked(stmt);
  return stmt;
}

void CQLServiceImpl::DeletePreparedStatement(const shared_ptr<const CQLStatement>& stmt) {
  // Get exclusive lock before deleting the prepared statement.
  std::lock_guard<std::mutex> guard(prepared_stmts_mutex_);

  DeletePreparedStatementUnlocked(stmt);

  VLOG(1) << "DeletePreparedStatement: CQL prepared statement cache count = "
          << prepared_stmts_map_.size() << "/" << prepared_stmts_list_.size()
          << ", memory usage = " << prepared_stmts_mem_tracker_->consumption();
}

void CQLServiceImpl::InsertLruPreparedStatementUnlocked(const shared_ptr<CQLStatement>& stmt) {
  // Insert the statement at the front of the LRU list.
  stmt->set_pos(prepared_stmts_list_.insert(prepared_stmts_list_.begin(), stmt));
}

void CQLServiceImpl::MoveLruPreparedStatementUnlocked(const shared_ptr<CQLStatement>& stmt) {
  // Move the statement to the front of the LRU list.
  prepared_stmts_list_.splice(prepared_stmts_list_.begin(), prepared_stmts_list_, stmt->pos());
}

void CQLServiceImpl::DeletePreparedStatementUnlocked(
    const std::shared_ptr<const CQLStatement> stmt) {
  // Remove statement from cache by looking it up by query ID and only when it is same statement
  // object. Note that the "stmt" parameter above is not a ref ("&") intentionally so that we have
  // a separate copy of the shared_ptr and not the very shared_ptr in prepared_stmts_map_ or
  // prepared_stmt_list_ we are deleting.
  const auto itr = prepared_stmts_map_.find(stmt->query_id());
  if (itr != prepared_stmts_map_.end() && itr->second == stmt) {
    prepared_stmts_map_.erase(itr);
  }
  // Remove statement from LRU list only when it is in the list, i.e. pos() != end().
  if (stmt->pos() != prepared_stmts_list_.end()) {
    prepared_stmts_list_.erase(stmt->pos());
    stmt->set_pos(prepared_stmts_list_.end());
  }
}

void CQLServiceImpl::DeleteLruPreparedStatement() {
  // Get exclusive lock before deleting the least recently used statement at the end of the LRU
  // list from the cache.
  std::lock_guard<std::mutex> guard(prepared_stmts_mutex_);

  if (!prepared_stmts_list_.empty()) {
    DeletePreparedStatementUnlocked(prepared_stmts_list_.back());
  }

  VLOG(1) << "DeleteLruPreparedStatement: CQL prepared statement cache count = "
          << prepared_stmts_map_.size() << "/" << prepared_stmts_list_.size()
          << ", memory usage = " << prepared_stmts_mem_tracker_->consumption();
}

}  // namespace cqlserver
}  // namespace yb
