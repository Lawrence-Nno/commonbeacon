# frozen_string_literal: true
# Run with Discourse 3.5.0: bundle exec rails runner /path/export_commonbeacon.rb
# Required environment: CB_CATEGORY_IDS, CB_SOURCE_INSTANCE_ID, CB_EXPORT_PATH.
# Read-only repeatable snapshot; creates one new private output file, never overwrites.
require "import_export"
require "securerandom"
require "open3"

commit = "05a304006600f36c3e45d19c9c5919f43f5541c9"
actual, status = Open3.capture2("git", "-C", Rails.root.to_s, "rev-parse", "HEAD")
raise "Unsupported Discourse source" unless Discourse::VERSION::STRING == "3.5.0" && status.success? && actual.strip == commit
site = ENV.fetch("CB_SOURCE_INSTANCE_ID")
raise "Invalid source instance UUID" unless site.match?(/\A[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\z/)
ids = ENV.fetch("CB_CATEGORY_IDS").split(",").map { |v| Integer(v, 10) }
raise "Choose 1-100 unique positive category IDs" unless ids.size.between?(1, 100) && ids.uniq == ids && ids.all?(&:positive?)
path = ENV.fetch("CB_EXPORT_PATH")
bundle = nil
ActiveRecord::Base.transaction(isolation: :repeatable_read) do
  ActiveRecord::Base.connection.execute("SET TRANSACTION READ ONLY")
  ActiveRecord::Base.connection.execute("SET LOCAL statement_timeout = '120s'")
  started = Time.now.utc.iso8601(6)
  raise "Missing selected category" unless Category.where(id: ids).count == ids.size
  scope = Category.where(id: ids).or(Category.where(parent_category_id: ids))
  raise "Private categories are unsupported" if scope.any?(&:read_restricted)
  raise "Category limit exceeded" if scope.count > 100
  topics = Topic.where(category_id: scope.pluck(:id)).where.not(id: scope.pluck(:topic_id).compact)
  raise "Topic limit exceeded" if topics.count > 5000
  source_posts = Post.where(topic_id: topics.select(:id))
  raise "Post limit exceeded" if source_posts.count > 25000
  raise "User limit exceeded" if source_posts.distinct.count(:user_id) > 2000
  raise "Source text exceeds bundle budget" if source_posts.sum("octet_length(raw)") > 8 * 1024 * 1024
  # Use the real upstream exporter, then explicitly project away contact/authority data.
  data = ImportExport::CategoryExporter.new(ids).perform.export_data.deep_stringify_keys
  categories = data.fetch("categories").map do |c|
    c.slice("id", "name", "slug", "description", "parent_category_id")
      .merge("public" => true, "created_at" => Category.find(c.fetch("id")).created_at.utc.iso8601(6))
  end
  users = data.fetch("users").map do |u|
    u.slice("id", "username").merge("created_at" => User.find(u.fetch("id")).created_at.utc.iso8601(6))
  end
  raise "User limit exceeded" if users.size > 2000
  exported_topics = data.fetch("topics").map do |t|
    model = Topic.find(t.fetch("id"))
    raise "Unsupported topic type" unless model.archetype == "regular"
    posts = t.fetch("posts").map do |p|
      post = Post.find(p.fetch("id"))
      raise "Unsupported post type or author" unless post.post_type == Post.types[:regular] && post.user_id.positive?
      p.slice("id", "user_id", "post_number", "raw", "created_at", "reply_to_post_number", "hidden")
        .merge("created_at" => post.created_at.utc.iso8601(6), "updated_at" => post.updated_at.utc.iso8601(6), "post_type" => post.post_type)
    end
    raise "Incomplete topic" unless posts.size == model.ordered_posts.count
    t.slice("id", "title", "category_id", "archetype", "created_at", "closed", "archived")
      .merge("created_at" => model.created_at.utc.iso8601(6), "visible" => model.visible, "updated_at" => [model.updated_at, model.first_post.updated_at].max.utc.iso8601(6),
             "posts" => posts, "post_count" => posts.size)
  end
  bundle = { bundleVersion: 1, sourceVersion: "3.5.0", sourceCommit: commit,
    sourceInstanceId: site, exportId: SecureRandom.uuid, snapshotStartedAt: started,
    snapshotCompletedAt: Time.now.utc.iso8601(6), categories: categories, users: users, topics: exported_topics,
    counts: { categories: categories.size, users: users.size, topics: exported_topics.size,
              posts: exported_topics.sum { |t| t.fetch("posts").size } } }
end
encoded = JSON.generate(bundle)
raise "Bundle exceeds 8 MiB" if encoded.bytesize > 8 * 1024 * 1024
File.open(path, File::WRONLY | File::CREAT | File::EXCL, 0o600) { |f| f.write(encoded) }
puts "CommonBeacon bundle written; review all compatibility losses before importing."
