// 백엔드 ApiResponse<T> 래퍼(common/response/ApiResponse.java)와 동일한 형태.
export type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  message: string | null;
};

// GithubRepositoryResponse (domain/repo/dto)와 필드 대응
export type GithubRepository = {
  id: number;
  name: string;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
  forkCount: number;
  openIssueCount: number;
  githubUrl: string;
  topics: string[];
  createdAt: string;
  updatedAt: string;
};

// TrendAnalysisResponse
export type TrendAnalysis = {
  repoId: number;
  fullName: string;
  analysis: string;
};

// RecommendedRepoDto
export type RecommendedRepo = {
  id: number;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
};

// RepoRecommendResponse
export type RepoRecommend = {
  stack: string;
  recommendation: string;
  candidates: RecommendedRepo[];
};

// FavoriteResponse (domain/user/dto)
export type Favorite = {
  repoId: number;
  fullName: string;
  description: string | null;
  language: string | null;
  starCount: number;
  githubUrl: string;
  favoritedAt: string;
};
