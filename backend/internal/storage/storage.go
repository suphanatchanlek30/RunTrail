// Package storage talks to S3 (or any S3-compatible server such as RustFS/MinIO in Docker).
package storage

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
)

// Object is what HeadObject tells us about an uploaded file.
type Object struct {
	Size        int64
	ContentType string
}

var ErrNotFound = errors.New("object not found")

// Storage is the small surface the API needs. Tests use an in-memory fake.
type Storage interface {
	PresignPut(ctx context.Context, key, contentType string, ttl time.Duration) (string, error)
	PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error)
	Head(ctx context.Context, key string) (Object, error)
	Delete(ctx context.Context, key string) error
	Ping(ctx context.Context) error
}

type Options struct {
	Bucket, Region           string
	Endpoint, PublicEndpoint string
	AccessKey, SecretKey     string
	PathStyle                bool
}

type S3 struct {
	bucket  string
	client  *s3.Client // server ↔ S3 (inside Docker: http://rustfs:9000)
	presign *s3.PresignClient
}

// New builds two clients: the signature covers the host, so URLs for phones must be
// signed against the public endpoint, not the Docker-internal one.
func New(ctx context.Context, o Options) (*S3, error) {
	loadOpts := []func(*awsconfig.LoadOptions) error{awsconfig.WithRegion(o.Region)}
	if o.AccessKey != "" {
		loadOpts = append(loadOpts, awsconfig.WithCredentialsProvider(
			credentials.NewStaticCredentialsProvider(o.AccessKey, o.SecretKey, "")))
	}
	cfg, err := awsconfig.LoadDefaultConfig(ctx, loadOpts...)
	if err != nil {
		return nil, fmt.Errorf("aws config: %w", err)
	}
	mk := func(endpoint string) *s3.Client {
		return s3.NewFromConfig(cfg, func(opt *s3.Options) {
			opt.UsePathStyle = o.PathStyle
			if endpoint != "" {
				opt.BaseEndpoint = aws.String(endpoint)
			}
		})
	}
	return &S3{
		bucket:  o.Bucket,
		client:  mk(o.Endpoint),
		presign: s3.NewPresignClient(mk(o.PublicEndpoint)),
	}, nil
}

// EnsureBucket creates the bucket when missing (local Docker only; on AWS Terraform owns it).
func (s *S3) EnsureBucket(ctx context.Context) error {
	if _, err := s.client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: &s.bucket}); err == nil {
		return nil
	}
	_, err := s.client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: &s.bucket})
	var owned *types.BucketAlreadyOwnedByYou
	if errors.As(err, &owned) {
		return nil
	}
	return err
}

func (s *S3) PresignPut(ctx context.Context, key, contentType string, ttl time.Duration) (string, error) {
	req, err := s.presign.PresignPutObject(ctx, &s3.PutObjectInput{
		Bucket: &s.bucket, Key: &key, ContentType: &contentType,
	}, s3.WithPresignExpires(ttl))
	if err != nil {
		return "", err
	}
	return req.URL, nil
}

func (s *S3) PresignGet(ctx context.Context, key string, ttl time.Duration) (string, error) {
	req, err := s.presign.PresignGetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key},
		s3.WithPresignExpires(ttl))
	if err != nil {
		return "", err
	}
	return req.URL, nil
}

func (s *S3) Head(ctx context.Context, key string) (Object, error) {
	out, err := s.client.HeadObject(ctx, &s3.HeadObjectInput{Bucket: &s.bucket, Key: &key})
	if err != nil {
		var nf *types.NotFound
		var api smithy.APIError
		if errors.As(err, &nf) || (errors.As(err, &api) && (api.ErrorCode() == "NotFound" || api.ErrorCode() == "NoSuchKey")) {
			return Object{}, ErrNotFound
		}
		return Object{}, err
	}
	return Object{Size: aws.ToInt64(out.ContentLength), ContentType: aws.ToString(out.ContentType)}, nil
}

func (s *S3) Delete(ctx context.Context, key string) error {
	_, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: &s.bucket, Key: &key})
	return err
}

func (s *S3) Ping(ctx context.Context) error {
	_, err := s.client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: &s.bucket})
	return err
}
